package com.gighub.document.service;

import com.gighub.document.mapper.ContractDocumentWriteMapper;
import com.gighub.document.mapper.param.DocumentInsertParam;
import com.gighub.document.mapper.param.DocumentVersionInsertParam;
import com.gighub.document.storage.DocumentStorageAdapter;
import com.gighub.document.storage.DocumentStorageIntegrityException;
import com.gighub.document.storage.HealthCertificateStorageKeys;
import com.gighub.document.validation.ValidatedHealthCertificateFile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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

    @AfterEach
    void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void deletesThePendingFileWhenTheSurroundingTransactionRollsBack() {
        stubGeneratedDocumentId();
        when(documentMapper.insertVersion(any())).thenReturn(1);
        TransactionSynchronizationManager.initSynchronization();

        HealthCertificateRegistrationHandle handle = transaction.register(
                new ValidatedHealthCertificateRegistration(
                        OWNER_ID, LocalDate.of(2026, 8, 14),
                        new ValidatedHealthCertificateFile(CONTENT, "jpg", "image/jpeg", CHECKSUM)));
        fireAfterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);

        verify(storageAdapter).deletePending(handle.pendingStorageKey());
    }

    @Test
    void keepsThePendingFileWhenTheSurroundingTransactionCommits() {
        stubGeneratedDocumentId();
        when(documentMapper.insertVersion(any())).thenReturn(1);
        TransactionSynchronizationManager.initSynchronization();

        transaction.register(new ValidatedHealthCertificateRegistration(
                OWNER_ID, LocalDate.of(2026, 8, 14),
                new ValidatedHealthCertificateFile(CONTENT, "jpg", "image/jpeg", CHECKSUM)));
        fireAfterCompletion(TransactionSynchronization.STATUS_COMMITTED);

        verify(storageAdapter, never()).deletePending(any());
    }

    @Test
    void pendingCleanupFailureDuringRollbackNeverEscapes() {
        stubGeneratedDocumentId();
        when(documentMapper.insertVersion(any())).thenReturn(1);
        doThrow(new RuntimeException("storage unavailable")).when(storageAdapter).deletePending(any());
        TransactionSynchronizationManager.initSynchronization();

        transaction.register(new ValidatedHealthCertificateRegistration(
                OWNER_ID, LocalDate.of(2026, 8, 14),
                new ValidatedHealthCertificateFile(CONTENT, "jpg", "image/jpeg", CHECKSUM)));

        fireAfterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
    }

    /**
     * 저장소는 Object를 만들고 일부만 기록한 뒤 실패할 수 있다. 정리 콜백이 쓰기 성공 뒤에만
     * 등록되면 그 부분 기록물이 대응하는 DB 행 없이 남으므로, 쓰기 실패 경로에서도 임시
     * Object가 정리되어야 한다.
     */
    @Test
    void cleansUpThePendingObjectWhenTheWriteItselfFails() {
        stubGeneratedDocumentId();
        when(documentMapper.insertVersion(any())).thenReturn(1);
        doThrow(new DocumentStorageIntegrityException("임시 계약 파일을 쓰지 못했습니다."))
                .when(storageAdapter).writePending(any(), any());
        TransactionSynchronizationManager.initSynchronization();

        assertThrows(DocumentStorageIntegrityException.class, () -> transaction.register(
                new ValidatedHealthCertificateRegistration(
                        OWNER_ID, LocalDate.of(2026, 8, 14),
                        new ValidatedHealthCertificateFile(CONTENT, "jpg", "image/jpeg", CHECKSUM))));

        String pendingKey = HealthCertificateStorageKeys.pendingKey(OWNER_ID, DOCUMENT_ID, "jpg");
        verify(storageAdapter).deletePending(pendingKey);

        // 쓰기 전에 등록된 콜백이 남아 있어 Rollback 확정 뒤에도 같은 Key를 다시 정리한다.
        fireAfterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
        verify(storageAdapter, times(2)).deletePending(pendingKey);
    }

    /** 활성 Transaction이 없어 콜백을 등록할 수 없는 경로에서도 쓰기 실패를 즉시 보상한다. */
    @Test
    void cleansUpThePendingObjectWhenTheWriteFailsWithoutAnActiveTransaction() {
        stubGeneratedDocumentId();
        when(documentMapper.insertVersion(any())).thenReturn(1);
        doThrow(new DocumentStorageIntegrityException("임시 계약 파일을 쓰지 못했습니다."))
                .when(storageAdapter).writePending(any(), any());

        assertThrows(DocumentStorageIntegrityException.class, () -> transaction.register(
                new ValidatedHealthCertificateRegistration(
                        OWNER_ID, LocalDate.of(2026, 8, 14),
                        new ValidatedHealthCertificateFile(CONTENT, "jpg", "image/jpeg", CHECKSUM))));

        verify(storageAdapter).deletePending(
                HealthCertificateStorageKeys.pendingKey(OWNER_ID, DOCUMENT_ID, "jpg"));
    }

    /** 쓰기 실패의 보상 자체가 실패해도 원래의 저장소 예외를 가리지 않는다. */
    @Test
    void keepsTheOriginalWriteFailureWhenTheImmediateCleanupAlsoFails() {
        stubGeneratedDocumentId();
        when(documentMapper.insertVersion(any())).thenReturn(1);
        doThrow(new DocumentStorageIntegrityException("임시 계약 파일을 쓰지 못했습니다."))
                .when(storageAdapter).writePending(any(), any());
        doThrow(new RuntimeException("storage unavailable")).when(storageAdapter).deletePending(any());

        DocumentStorageIntegrityException thrown = assertThrows(
                DocumentStorageIntegrityException.class, () -> transaction.register(
                        new ValidatedHealthCertificateRegistration(
                                OWNER_ID, LocalDate.of(2026, 8, 14),
                                new ValidatedHealthCertificateFile(
                                        CONTENT, "jpg", "image/jpeg", CHECKSUM))));

        assertEquals("임시 계약 파일을 쓰지 못했습니다.", thrown.getMessage());
    }

    private void fireAfterCompletion(int status) {
        List<TransactionSynchronization> synchronizations =
                TransactionSynchronizationManager.getSynchronizations();
        synchronizations.forEach(synchronization -> synchronization.afterCompletion(status));
    }

    private void stubGeneratedDocumentId() {
        doAnswer(invocation -> {
            DocumentInsertParam param = invocation.getArgument(0);
            param.setId(DOCUMENT_ID);
            return 1;
        }).when(documentMapper).insertDocument(any());
    }
}
