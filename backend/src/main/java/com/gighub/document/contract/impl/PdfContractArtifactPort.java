package com.gighub.document.contract.impl;

import com.gighub.common.api.ApiTimes;
import com.gighub.contract.ContractArtifactCommand;
import com.gighub.contract.ContractArtifactHandle;
import com.gighub.contract.ContractArtifactPort;
import com.gighub.contract.domain.ContractTermsSnapshot;
import com.gighub.document.contract.ContractPdfRenderer;
import com.gighub.document.contract.ContractSnapshot;
import com.gighub.document.mapper.ContractDocumentWriteMapper;
import com.gighub.document.mapper.param.DocumentInsertParam;
import com.gighub.document.mapper.param.DocumentShareInsertParam;
import com.gighub.document.mapper.param.DocumentSignatureInsertParam;
import com.gighub.document.mapper.param.DocumentVersionInsertParam;
import com.gighub.document.mapper.result.ContractVersionPromotionRow;
import com.gighub.document.storage.ContractStorageKeys;
import com.gighub.document.storage.DocumentStorageAdapter;
import com.gighub.document.storage.Sha256;
import com.gighub.member.service.MemberIdentityQueryService;
import com.gighub.member.service.result.MemberIdentitySnapshot;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 근로계약서 ORIGINAL·SIGNED Version을 자동 생성하고 비공개 Storage에 보관합니다
 * (DEC-CONTRACT-AUTO-GENERATION, DEC-DOCUMENT-STORAGE).
 *
 * <p>WORKER의 초대 수락 자체가 서명 의사 표시라, {@link #prepare}가 서명 없는 ORIGINAL과
 * WORKER 이름을 굳힌 SIGNED를 함께 만든다. 별도의 뒤이은 서명 절차는 없다.</p>
 *
 * <p>{@link #prepare}는 수락 Transaction 안에서 파일을 임시 Key에 쓰고 {@code documents}·
 * {@code document_versions}·{@code document_signatures}·{@code document_shares} 행을
 * 만든다. 실패하면 그대로 던져 수락 전체를 Rollback시킨다. {@link #promote}와
 * {@link #discardPending}은 이미 확정된 수락을 뒤집을 수 없으므로 실패를 삼키고 기록만
 * 남긴다.</p>
 */
@Component
@RequiredArgsConstructor
public class PdfContractArtifactPort implements ContractArtifactPort {

    private static final Logger log = LoggerFactory.getLogger(PdfContractArtifactPort.class);

    private static final String DOCUMENT_TYPE = "EMPLOYMENT_CONTRACT";
    private static final String DOCUMENT_STATUS_AWAITING_SIGNATURE = "AWAITING_SIGNATURE";
    private static final String DOCUMENT_STATUS_SIGNED = "SIGNED";
    private static final String DOCUMENT_STATUS_ACTIVE = "ACTIVE";
    private static final String VERSION_ORIGINAL = "ORIGINAL";
    private static final String VERSION_SIGNED = "SIGNED";
    private static final String SIGNATURE_METHOD_TYPED_NAME = "TYPED_NAME";
    private static final String MIME_TYPE_PDF = "application/pdf";
    private static final String SHARE_PURPOSE_CONTRACT_PARTY = "CONTRACT_PARTY";
    private static final int VERSION_NO_ORIGINAL = 1;
    private static final int VERSION_NO_SIGNED = 2;

    private final ContractDocumentWriteMapper documentMapper;
    private final ContractPdfRenderer renderer;
    private final DocumentStorageAdapter storageAdapter;
    private final MemberIdentityQueryService memberIdentityQueryService;

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public ContractArtifactHandle prepare(ContractArtifactCommand command) {
        ContractTermsSnapshot terms = command.getTerms();

        ContractSnapshot snapshot = toRenderSnapshot(command, terms);
        byte[] originalBytes = renderer.render(snapshot);
        byte[] signedBytes = renderer.render(
                snapshot,
                new ContractSnapshot.Signature(terms.getWorker().getName(), command.getAcceptedAt()));
        byte[] originalChecksum = Sha256.digest(originalBytes);
        byte[] signedChecksum = Sha256.digest(signedBytes);

        long documentId = insertDocument(command, terms);
        long originalVersionId = insertVersion(
                documentId, command.getWorkCaseId(), VERSION_NO_ORIGINAL, VERSION_ORIGINAL,
                originalBytes, originalChecksum);
        long signedVersionId = insertVersion(
                documentId, command.getWorkCaseId(), VERSION_NO_SIGNED, VERSION_SIGNED,
                signedBytes, signedChecksum);
        insertSignature(
                documentId, originalVersionId, signedVersionId, terms, command.getAcceptedAt(),
                originalChecksum, signedChecksum);
        insertShare(documentId, command.getWorkCaseId(), terms);

        storageAdapter.writePending(
                ContractStorageKeys.pendingKey(
                        command.getWorkCaseId(), documentId, VERSION_NO_ORIGINAL),
                originalBytes);
        storageAdapter.writePending(
                ContractStorageKeys.pendingKey(
                        command.getWorkCaseId(), documentId, VERSION_NO_SIGNED),
                signedBytes);

        updateDocumentStatus(
                documentId, DOCUMENT_STATUS_AWAITING_SIGNATURE, DOCUMENT_STATUS_SIGNED);
        updateDocumentStatus(documentId, DOCUMENT_STATUS_SIGNED, DOCUMENT_STATUS_ACTIVE);

        return ContractArtifactHandle.of(command.getWorkCaseId(), command.getContractId());
    }

    @Override
    public void promote(ContractArtifactHandle handle) {
        if (handle.getWorkCaseId() == 0 && handle.getContractId() == 0) {
            return;
        }
        try {
            List<ContractVersionPromotionRow> rows =
                    documentMapper.findPromotionRowsByWorkCaseId(handle.getWorkCaseId());
            for (ContractVersionPromotionRow row : rows) {
                promoteOne(handle.getWorkCaseId(), row);
            }
        } catch (RuntimeException failure) {
            // 승격 실패는 이미 확정된 수락을 되돌리지 않는다. 조회 시점에 임시 Object를
            // 검증해 Fallback하고 승격을 다시 시도한다.
            log.warn("계약서 파일 승격에 실패했습니다. workCaseId={}", handle.getWorkCaseId(), failure);
        }
    }

    private void promoteOne(long workCaseId, ContractVersionPromotionRow row) {
        try {
            storageAdapter.promote(
                    ContractStorageKeys.pendingKey(
                            workCaseId, row.getDocumentId(), row.getVersionNo()),
                    row.getStorageKey(),
                    row.getChecksum());
        } catch (RuntimeException failure) {
            log.warn(
                    "계약서 파일 한 Version 승격에 실패했습니다. workCaseId={}, versionType={}",
                    workCaseId, row.getVersionType(), failure);
        }
    }

    @Override
    public void discardPending(long workCaseId) {
        try {
            // 동시 수락 패자는 승자의 Commit 뒤에 도착할 수 있습니다. DB에 남은 문서의
            // 임시 Fallback은 보존하고 Rollback으로 사라진 문서 경로만 정리합니다.
            Set<Long> retainedDocumentIds = documentMapper
                    .findPromotionRowsByWorkCaseId(workCaseId)
                    .stream()
                    .map(ContractVersionPromotionRow::getDocumentId)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toSet());
            storageAdapter.deletePendingByWorkCaseId(workCaseId, retainedDocumentIds);
        } catch (RuntimeException failure) {
            log.warn("임시 계약서 파일 정리에 실패했습니다. workCaseId={}", workCaseId, failure);
        }
    }

    private ContractSnapshot toRenderSnapshot(
            ContractArtifactCommand command, ContractTermsSnapshot terms) {
        return new ContractSnapshot(
                command.getWorkCaseId(),
                terms.getTitle(),
                ApiTimes.toLocalDateTime(terms.getStartsAt()),
                ApiTimes.toLocalDateTime(terms.getEndsAt()),
                terms.getBreakMinutes(),
                terms.isBreakPaid(),
                terms.getWorkplaceName(),
                terms.getWorkplaceAddress(),
                terms.getDailyWage(),
                terms.getOwner().getName(),
                findPhone(terms.getOwner().getUserId()),
                terms.getWorker().getName(),
                findPhone(terms.getWorker().getUserId()),
                terms.getTermsVersion(),
                command.getAcceptedAt());
    }

    /**
     * 계약서 표시용으로만 쓰는 현재 연락처를 읽는다.
     *
     * <p>{@code work_contracts.terms_snapshot}의 승인된 JSON Shape에는 연락처가 없어 계약
     * 확정 시점에 굳히지 않는다. 연락처는 선택 입력이라 없을 수 있다. {@code member} 모듈의
     * Mapper를 직접 참조하지 않고 공개 경계인 {@link MemberIdentityQueryService}만 쓴다.</p>
     */
    private String findPhone(long userId) {
        MemberIdentitySnapshot identity = memberIdentityQueryService.findById(userId);
        return identity == null ? null : identity.phone();
    }

    private long insertDocument(ContractArtifactCommand command, ContractTermsSnapshot terms) {
        DocumentInsertParam param = DocumentInsertParam.builder()
                .createdByUserId(terms.getOwner().getUserId())
                .ownerUserId(terms.getOwner().getUserId())
                .workCaseId(command.getWorkCaseId())
                .documentType(DOCUMENT_TYPE)
                .status(DOCUMENT_STATUS_AWAITING_SIGNATURE)
                .issuedOn(command.getAcceptedAt().toLocalDate())
                .build();
        if (documentMapper.insertDocument(param) != 1) {
            throw new IllegalStateException("계약서 문서 행을 저장하지 못했습니다.");
        }
        return Objects.requireNonNull(param.getId(), "생성된 문서 식별자");
    }

    private long insertVersion(
            long documentId, long workCaseId, int versionNo, String versionType,
            byte[] content, byte[] checksum) {
        DocumentVersionInsertParam param = DocumentVersionInsertParam.builder()
                .documentId(documentId)
                .versionNo(versionNo)
                .versionType(versionType)
                .storageKey(ContractStorageKeys.finalKey(workCaseId, documentId, versionNo))
                .mimeType(MIME_TYPE_PDF)
                .sizeBytes((long) content.length)
                .checksum(checksum)
                .build();
        if (documentMapper.insertVersion(param) != 1) {
            throw new IllegalStateException("계약서 파일 Version 행을 저장하지 못했습니다.");
        }
        return Objects.requireNonNull(param.getId(), "생성된 문서 Version 식별자");
    }

    private void insertSignature(
            long documentId, long originalVersionId, long signedVersionId,
            ContractTermsSnapshot terms, LocalDateTime acceptedAt,
            byte[] originalChecksum, byte[] signedChecksum) {
        DocumentSignatureInsertParam param = DocumentSignatureInsertParam.builder()
                .documentId(documentId)
                .sourceVersionId(originalVersionId)
                .signedVersionId(signedVersionId)
                .signerUserId(terms.getWorker().getUserId())
                .sourceChecksum(originalChecksum)
                .signedChecksum(signedChecksum)
                .typedName(terms.getWorker().getName())
                .signatureMethod(SIGNATURE_METHOD_TYPED_NAME)
                .consentedAt(acceptedAt)
                .signedAt(acceptedAt)
                .build();
        if (documentMapper.insertSignature(param) != 1) {
            throw new IllegalStateException("계약서 서명 행을 저장하지 못했습니다.");
        }
    }

    private void insertShare(long documentId, long workCaseId, ContractTermsSnapshot terms) {
        DocumentShareInsertParam param = DocumentShareInsertParam.builder()
                .documentId(documentId)
                .workCaseId(workCaseId)
                .sharedWithUserId(terms.getWorker().getUserId())
                .purpose(SHARE_PURPOSE_CONTRACT_PARTY)
                .build();
        if (documentMapper.insertShare(param) != 1) {
            throw new IllegalStateException("계약서 공유 행을 저장하지 못했습니다.");
        }
    }

    private void updateDocumentStatus(long documentId, String expectedStatus, String status) {
        if (documentMapper.updateDocumentStatus(documentId, expectedStatus, status) != 1) {
            throw new IllegalStateException("계약서 문서 상태를 전이하지 못했습니다.");
        }
    }
}
