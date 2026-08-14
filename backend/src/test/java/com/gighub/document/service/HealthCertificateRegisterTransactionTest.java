package com.gighub.document.service;

import com.gighub.document.mapper.ContractDocumentWriteMapper;
import com.gighub.document.mapper.param.DocumentInsertParam;
import com.gighub.document.mapper.param.DocumentVersionInsertParam;
import com.gighub.document.storage.DocumentStorageAdapter;
import com.gighub.document.storage.HealthCertificateStorageKeys;
import com.gighub.document.validation.ValidatedHealthCertificateFile;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 보건증 등록 한 건이 {@code documents}·{@code document_versions} 행을 만들고 임시 Key에
 * 파일을 쓰는지 확인합니다. 재등록이 항상 새 문서를 만든다는 DEC-HEALTH-CERTIFICATE-LIFECYCLE
 * 전제는 매번 새 documentId를 발급하는 이 흐름 자체로 만족된다.
 */
class HealthCertificateRegisterTransactionTest {

    private static final long OWNER_ID = 1L;
    private static final long DOCUMENT_ID = 9L;
    private static final byte[] CONTENT = new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};
    private static final byte[] CHECKSUM = new byte[]{1, 2, 3};

    private final ContractDocumentWriteMapper documentMapper = mock(ContractDocumentWriteMapper.class);
    private final DocumentStorageAdapter storageAdapter = mock(DocumentStorageAdapter.class);

    private final HealthCertificateRegisterTransaction transaction =
            new HealthCertificateRegisterTransaction(documentMapper, storageAdapter);

    @Test
    void insertsTheDocumentAndOriginalVersionThenWritesThePendingFile() {
        stubGeneratedDocumentId();
        when(documentMapper.insertVersion(any())).thenReturn(1);
        LocalDate issuedDate = LocalDate.of(2026, 8, 14);

        HealthCertificateRegistrationHandle handle = transaction.register(
                new ValidatedHealthCertificateRegistration(
                        OWNER_ID, issuedDate,
                        new ValidatedHealthCertificateFile(CONTENT, "jpg", "image/jpeg", CHECKSUM)));

        assertEquals(DOCUMENT_ID, handle.documentId());
        assertEquals(LocalDate.of(2027, 8, 14), handle.expiresDate());
        assertEquals(
                HealthCertificateStorageKeys.finalKey(OWNER_ID, DOCUMENT_ID, "jpg"),
                handle.finalStorageKey());
        assertEquals(
                HealthCertificateStorageKeys.pendingKey(OWNER_ID, DOCUMENT_ID, "jpg"),
                handle.pendingStorageKey());

        ArgumentCaptor<DocumentInsertParam> documentCaptor =
                ArgumentCaptor.forClass(DocumentInsertParam.class);
        verify(documentMapper).insertDocument(documentCaptor.capture());
        assertEquals(OWNER_ID, documentCaptor.getValue().getOwnerUserId());
        assertEquals(OWNER_ID, documentCaptor.getValue().getCreatedByUserId());
        assertEquals("HEALTH_CERTIFICATE", documentCaptor.getValue().getDocumentType());
        assertEquals("ACTIVE", documentCaptor.getValue().getStatus());
        assertEquals(issuedDate, documentCaptor.getValue().getIssuedOn());
        assertEquals(LocalDate.of(2027, 8, 14), documentCaptor.getValue().getExpiresOn());

        ArgumentCaptor<DocumentVersionInsertParam> versionCaptor =
                ArgumentCaptor.forClass(DocumentVersionInsertParam.class);
        verify(documentMapper).insertVersion(versionCaptor.capture());
        assertEquals(DOCUMENT_ID, versionCaptor.getValue().getDocumentId());
        assertEquals(1, versionCaptor.getValue().getVersionNo());
        assertEquals("ORIGINAL", versionCaptor.getValue().getVersionType());
        assertEquals(
                HealthCertificateStorageKeys.finalKey(OWNER_ID, DOCUMENT_ID, "jpg"),
                versionCaptor.getValue().getStorageKey());

        verify(storageAdapter).writePending(
                HealthCertificateStorageKeys.pendingKey(OWNER_ID, DOCUMENT_ID, "jpg"), CONTENT);
    }

    @Test
    void doesNotWriteAnyFileWhenTheVersionInsertFails() {
        stubGeneratedDocumentId();
        doThrow(new IllegalStateException("boom")).when(documentMapper).insertVersion(any());

        assertThrows(IllegalStateException.class, () -> transaction.register(
                new ValidatedHealthCertificateRegistration(
                        OWNER_ID, LocalDate.of(2026, 8, 14),
                        new ValidatedHealthCertificateFile(CONTENT, "jpg", "image/jpeg", CHECKSUM))));

        verify(storageAdapter, never()).writePending(any(), any());
    }

    private void stubGeneratedDocumentId() {
        doAnswer(invocation -> {
            DocumentInsertParam param = invocation.getArgument(0);
            param.setId(DOCUMENT_ID);
            return 1;
        }).when(documentMapper).insertDocument(any());
    }
}
