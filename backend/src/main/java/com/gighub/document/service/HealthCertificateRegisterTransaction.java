package com.gighub.document.service;

import com.gighub.document.mapper.ContractDocumentWriteMapper;
import com.gighub.document.mapper.param.DocumentInsertParam;
import com.gighub.document.mapper.param.DocumentVersionInsertParam;
import com.gighub.document.storage.DocumentStorageAdapter;
import com.gighub.document.storage.HealthCertificateStorageKeys;
import com.gighub.document.validation.ValidatedHealthCertificateFile;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

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
 */
@Component
@RequiredArgsConstructor
public class HealthCertificateRegisterTransaction {

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

        return new HealthCertificateRegistrationHandle(
                documentId, pendingKey, finalKey, file.checksum(), expiresDate);
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
