package com.gighub.document.service;

import com.gighub.document.mapper.ContractDocumentWriteMapper;
import com.gighub.document.mapper.param.DocumentInsertParam;
import com.gighub.document.mapper.param.DocumentVersionInsertParam;
import com.gighub.document.storage.DocumentStorageAdapter;
import com.gighub.document.storage.HealthCertificateStorageKeys;
import com.gighub.document.validation.ValidatedHealthCertificateFile;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDate;
import java.util.Objects;

/**
 * 보건증 등록 한 건의 {@code documents}·{@code document_versions} 행을 원자적으로 만듭니다
 * (DEC-HEALTH-CERTIFICATE-LIFECYCLE, DOC-005). 재등록은 항상 새 문서와 ORIGINAL Version
 * 1을 만들며 기존 보건증에 Version을 추가하지 않는다.
 *
 * <p>{@code documents}·{@code document_versions}의 단일 writer는
 * {@link ContractDocumentWriteMapper}이다(MODULE_BOUNDARIES.md). 근로계약서 자동 생성과
 * 같은 Mapper를 공유하되 서로 다른 {@code documentType}·Transaction 경계를 쓴다.</p>
 *
 * <p>임시 Object는 DB Commit 전에 쓰므로, Commit 자체가 실패해 이 Transaction이 최종
 * Rollback되면 임시 파일만 저장소에 남는다. {@link TransactionSynchronization}으로 실제
 * Commit·Rollback 결과가 확정된 뒤에만 정리해, 저장소 성공·DB 실패 조합에서도 임시 Object가
 * 영구히 남지 않게 한다(저장소 성공/DB 실패 보상).</p>
 */
@Component
@RequiredArgsConstructor
public class HealthCertificateRegisterTransaction {

    private static final Logger log = LoggerFactory.getLogger(HealthCertificateRegisterTransaction.class);

    private static final String DOCUMENT_TYPE = "HEALTH_CERTIFICATE";
    private static final String DOCUMENT_STATUS_ACTIVE = "ACTIVE";
    private static final String VERSION_TYPE_ORIGINAL = "ORIGINAL";
    private static final int VERSION_NO_ORIGINAL = 1;

    private final ContractDocumentWriteMapper documentMapper;
    private final DocumentStorageAdapter storageAdapter;

    @Transactional
    public HealthCertificateRegistrationHandle register(
            ValidatedHealthCertificateRegistration request) {
        long ownerUserId = request.ownerUserId();
        ValidatedHealthCertificateFile file = request.file();
        LocalDate expiresDate = request.issuedDate().plusYears(1);

        long documentId = insertDocument(ownerUserId, request.issuedDate(), expiresDate);
        String finalKey = HealthCertificateStorageKeys.finalKey(
                ownerUserId, documentId, file.storageExtension());
        String pendingKey = HealthCertificateStorageKeys.pendingKey(
                ownerUserId, documentId, file.storageExtension());

        insertVersion(documentId, finalKey, file);
        storageAdapter.writePending(pendingKey, file.content());
        registerPendingCleanupOnRollback(pendingKey);

        return new HealthCertificateRegistrationHandle(
                documentId, pendingKey, finalKey, file.checksum(), expiresDate);
    }

    /**
     * 이 Transaction이 Commit되지 않으면(Rollback·Commit 실패 모두 포함) 방금 쓴 임시
     * Object를 정리한다. 단위 테스트처럼 실제 Transaction Proxy 밖에서 직접 호출될 때는
     * 등록할 활성 Transaction이 없어 아무 일도 하지 않는다.
     */
    private void registerPendingCleanupOnRollback(String pendingKey) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == TransactionSynchronization.STATUS_COMMITTED) {
                    return;
                }
                try {
                    storageAdapter.deletePending(pendingKey);
                } catch (RuntimeException failure) {
                    log.warn("Rollback된 보건증 임시 파일 정리에 실패했습니다.", failure);
                }
            }
        });
    }

    private long insertDocument(long ownerUserId, LocalDate issuedDate, LocalDate expiresDate) {
        DocumentInsertParam param = DocumentInsertParam.builder()
                .createdByUserId(ownerUserId)
                .ownerUserId(ownerUserId)
                .workCaseId(null)
                .documentType(DOCUMENT_TYPE)
                .status(DOCUMENT_STATUS_ACTIVE)
                .issuedOn(issuedDate)
                .expiresOn(expiresDate)
                .build();
        if (documentMapper.insertDocument(param) != 1) {
            throw new IllegalStateException("보건증 문서 행을 저장하지 못했습니다.");
        }
        return Objects.requireNonNull(param.getId(), "생성된 문서 식별자");
    }

    private void insertVersion(long documentId, String finalKey, ValidatedHealthCertificateFile file) {
        DocumentVersionInsertParam param = DocumentVersionInsertParam.builder()
                .documentId(documentId)
                .versionNo(VERSION_NO_ORIGINAL)
                .versionType(VERSION_TYPE_ORIGINAL)
                .storageKey(finalKey)
                .mimeType(file.mimeType())
                .sizeBytes((long) file.content().length)
                .checksum(file.checksum())
                .build();
        if (documentMapper.insertVersion(param) != 1) {
            throw new IllegalStateException("보건증 파일 Version 행을 저장하지 못했습니다.");
        }
    }
}
