package com.gighub.document.service;

import com.gighub.document.exception.DocumentNotFoundException;
import com.gighub.document.mapper.DocumentAccessMapper;
import com.gighub.document.mapper.param.DocumentAccessLogParam;
import com.gighub.document.mapper.result.DocumentFileAccessRow;
import com.gighub.document.storage.ContractStorageKeys;
import com.gighub.document.storage.DocumentStorageAdapter;
import com.gighub.document.storage.DocumentStorageIntegrityException;
import com.gighub.document.storage.Sha256;
import com.gighub.member.domain.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DocumentFileAccessServiceTest {

    private static final Long DOCUMENT_ID = 10L;
    private static final Long VERSION_ID = 55L;
    private static final Long WORK_CASE_ID = 1L;
    private static final Long OWNER_ID = 3L;
    private static final Long WORKER_ID = 4L;
    private static final Long STRANGER_ID = 99L;
    private static final byte[] CONTENT = new byte[]{1, 2, 3};
    private static final String CONTRACT_FINAL_KEY = "contracts/1/10/v2.pdf";
    private static final String CONTRACT_PENDING_KEY =
            ContractStorageKeys.pendingKey(WORK_CASE_ID, DOCUMENT_ID, 2);
    private static final String HEALTH_FINAL_KEY = "health-certificates/4/10/v1.jpg";
    private static final String HEALTH_PENDING_KEY =
            "health-certificates/4/10/.pending/v1.jpg";
    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-08-11T03:00:00Z"),
            ZoneId.of("Asia/Seoul"));

    @Mock
    private DocumentAccessMapper documentAccessMapper;
    @Mock
    private DocumentStorageAdapter storageAdapter;

    private DocumentFileAccessService service;

    @BeforeEach
    void setUp() {
        DocumentFileAccessTransaction accessTransaction =
                new DocumentFileAccessTransaction(documentAccessMapper, CLOCK);
        service = new DocumentFileAccessService(accessTransaction, storageAdapter);
        lenient().when(documentAccessMapper.insertAccessLog(any())).thenReturn(1);
    }

    @Test
    void contractOwnerLoadsSignedVersionAndAllowedAuditIsWritten() {
        givenReadable(contractRow("ACTIVE", "application/pdf"));

        DocumentFileResult result = service.loadFile(
                DOCUMENT_ID, OWNER_ID, UserRole.OWNER, "view");

        assertArrayEquals(CONTENT, result.getContent());
        assertEquals("application/pdf", result.getMimeType());
        assertEquals("employment-contract.pdf", result.getAsciiFileName());
        assertFalse(result.isForceAttachment());
        assertAudit("CONTRACT_FILE_VIEW", "ALLOWED", null, VERSION_ID, OWNER_ID);
    }

    @Test
    void contractWorkerGetsTheSameSignedBytesForDownload() {
        givenReadable(contractRow("ACTIVE", "application/pdf"));

        DocumentFileResult result = service.loadFile(
                DOCUMENT_ID, WORKER_ID, UserRole.WORKER, "download");

        assertArrayEquals(CONTENT, result.getContent());
        assertAudit("CONTRACT_FILE_DOWNLOAD", "ALLOWED", null, VERSION_ID, WORKER_ID);
    }

    @Test
    void strangerGetsNotFoundAndDeniedAuditBeforeStorageRead() {
        when(documentAccessMapper.lockFileAccessContext(DOCUMENT_ID))
                .thenReturn(contractRow("ACTIVE", "application/pdf"));

        assertThrows(DocumentNotFoundException.class, () -> service.loadFile(
                DOCUMENT_ID, STRANGER_ID, UserRole.WORKER, "view"));

        verify(storageAdapter, never()).read(any());
        assertAudit("CONTRACT_FILE_VIEW", "DENIED", "PARTY_ACCESS_DENIED",
                VERSION_ID, STRANGER_ID);
    }

    @Test
    void missingDocumentDoesNotCreateAForeignKeyAuditRow() {
        when(documentAccessMapper.lockFileAccessContext(DOCUMENT_ID)).thenReturn(null);

        assertThrows(DocumentNotFoundException.class, () -> service.loadFile(
                DOCUMENT_ID, OWNER_ID, UserRole.OWNER, "view"));

        verify(documentAccessMapper, never()).insertAccessLog(any());
    }

    @Test
    void inactiveDocumentIsAuditedAndHiddenAsNotFound() {
        when(documentAccessMapper.lockFileAccessContext(DOCUMENT_ID))
                .thenReturn(contractRow("CANCELED", "application/pdf"));

        assertThrows(DocumentNotFoundException.class, () -> service.loadFile(
                DOCUMENT_ID, OWNER_ID, UserRole.OWNER, "view"));

        assertAudit("CONTRACT_FILE_VIEW", "DENIED", "DOCUMENT_UNAVAILABLE",
                VERSION_ID, OWNER_ID);
    }

    @Test
    void healthOwnerCanReadAnExpiredCertificate() {
        givenReadable(healthRow(LocalDate.of(2026, 8, 10), "image/png"));

        DocumentFileResult result = service.loadFile(
                DOCUMENT_ID, WORKER_ID, UserRole.WORKER, "view");

        assertArrayEquals(CONTENT, result.getContent());
        assertEquals("image/png", result.getMimeType());
        assertAudit("HEALTH_CERT_FILE_VIEW", "ALLOWED", null, VERSION_ID, WORKER_ID);
        verify(documentAccessMapper, never()).lockValidHealthShare(any(), any(), any(), any());
    }

    @Test
    void ownerWithValidLockedHealthShareCanRead() {
        givenReadable(healthRow(LocalDate.of(2027, 8, 11), "image/jpeg"));
        when(documentAccessMapper.lockValidHealthShare(
                DOCUMENT_ID,
                OWNER_ID,
                java.time.LocalDateTime.of(2026, 8, 11, 12, 0),
                LocalDate.of(2026, 8, 11)))
                .thenReturn(91L);

        DocumentFileResult result = service.loadFile(
                DOCUMENT_ID, OWNER_ID, UserRole.OWNER, "download");

        assertArrayEquals(CONTENT, result.getContent());
        assertAudit("HEALTH_CERT_FILE_DOWNLOAD", "ALLOWED", null, VERSION_ID, OWNER_ID);
    }

    @Test
    void revokedOrExpiredShareIsAuditedUnavailableAndHidden() {
        when(documentAccessMapper.lockFileAccessContext(DOCUMENT_ID))
                .thenReturn(healthRow(LocalDate.of(2027, 8, 11), "image/jpeg"));
        when(documentAccessMapper.lockValidHealthShare(any(), any(), any(), any()))
                .thenReturn(null);
        when(documentAccessMapper.hasHealthShareHistory(DOCUMENT_ID, OWNER_ID))
                .thenReturn(true);

        assertThrows(DocumentNotFoundException.class, () -> service.loadFile(
                DOCUMENT_ID, OWNER_ID, UserRole.OWNER, "view"));

        verify(storageAdapter, never()).read(any());
        assertAudit("HEALTH_CERT_FILE_VIEW", "DENIED", "DOCUMENT_UNAVAILABLE",
                VERSION_ID, OWNER_ID);
    }

    @Test
    void unrelatedUserGetsSameNotFoundShapeWithPartyReasonInAudit() {
        when(documentAccessMapper.lockFileAccessContext(DOCUMENT_ID))
                .thenReturn(healthRow(LocalDate.of(2027, 8, 11), "image/jpeg"));
        when(documentAccessMapper.hasHealthShareHistory(DOCUMENT_ID, STRANGER_ID))
                .thenReturn(false);

        assertThrows(DocumentNotFoundException.class, () -> service.loadFile(
                DOCUMENT_ID, STRANGER_ID, UserRole.WORKER, "view"));

        assertAudit("HEALTH_CERT_FILE_VIEW", "DENIED", "PARTY_ACCESS_DENIED",
                VERSION_ID, STRANGER_ID);
    }

    @Test
    void missingSignedVersionIsAuditedAsInternalIntegrityFailure() {
        when(documentAccessMapper.lockFileAccessContext(DOCUMENT_ID))
                .thenReturn(contractRowWithoutVersion());

        assertThrows(DocumentStorageIntegrityException.class, () -> service.loadFile(
                DOCUMENT_ID, OWNER_ID, UserRole.OWNER, "view"));

        assertAudit("CONTRACT_FILE_VIEW", "DENIED", "SIGNED_VERSION_UNAVAILABLE",
                null, OWNER_ID);
    }

    @Test
    void strangerCannotDistinguishMissingSignedVersionFromAnInvisibleDocument() {
        when(documentAccessMapper.lockFileAccessContext(DOCUMENT_ID))
                .thenReturn(contractRowWithoutVersion());

        assertThrows(DocumentNotFoundException.class, () -> service.loadFile(
                DOCUMENT_ID, STRANGER_ID, UserRole.WORKER, "view"));

        verify(storageAdapter, never()).read(any());
        assertAudit("CONTRACT_FILE_VIEW", "DENIED", "PARTY_ACCESS_DENIED",
                null, STRANGER_ID);
    }

    @Test
    void checksumMismatchIsAuditedAndNothingIsReturned() {
        DocumentFileAccessRow row = contractRow("ACTIVE", "application/pdf");
        when(documentAccessMapper.lockFileAccessContext(DOCUMENT_ID)).thenReturn(row);
        when(storageAdapter.exists(CONTRACT_FINAL_KEY)).thenReturn(true);
        when(storageAdapter.read(CONTRACT_FINAL_KEY)).thenReturn(new byte[]{9});
        when(storageAdapter.exists(CONTRACT_PENDING_KEY)).thenReturn(false);

        assertThrows(DocumentStorageIntegrityException.class, () -> service.loadFile(
                DOCUMENT_ID, OWNER_ID, UserRole.OWNER, "view"));

        assertAudit("CONTRACT_FILE_VIEW", "DENIED", "CHECKSUM_MISMATCH",
                VERSION_ID, OWNER_ID);
    }

    @Test
    void verifiedContractPendingBytesAreReturnedAndPromotionIsRetried() {
        DocumentFileAccessRow row = contractRow("ACTIVE", "application/pdf");
        when(documentAccessMapper.lockFileAccessContext(DOCUMENT_ID)).thenReturn(row);
        when(storageAdapter.exists(CONTRACT_FINAL_KEY)).thenReturn(false);
        when(storageAdapter.exists(CONTRACT_PENDING_KEY)).thenReturn(true);
        when(storageAdapter.read(CONTRACT_PENDING_KEY)).thenReturn(CONTENT);

        DocumentFileResult result = service.loadFile(
                DOCUMENT_ID, OWNER_ID, UserRole.OWNER, "view");

        assertArrayEquals(CONTENT, result.getContent());
        verify(storageAdapter).promote(
                CONTRACT_PENDING_KEY, CONTRACT_FINAL_KEY, Sha256.digest(CONTENT));
        assertAudit("CONTRACT_FILE_VIEW", "ALLOWED", null, VERSION_ID, OWNER_ID);
    }

    @Test
    void verifiedPendingBytesRemainAvailableWhenPromotionFails() {
        DocumentFileAccessRow row = contractRow("ACTIVE", "application/pdf");
        when(documentAccessMapper.lockFileAccessContext(DOCUMENT_ID)).thenReturn(row);
        when(storageAdapter.exists(CONTRACT_FINAL_KEY)).thenReturn(false);
        when(storageAdapter.exists(CONTRACT_PENDING_KEY)).thenReturn(true);
        when(storageAdapter.read(CONTRACT_PENDING_KEY)).thenReturn(CONTENT);
        doThrow(new DocumentStorageIntegrityException("boom"))
                .when(storageAdapter).promote(any(), any(), any());

        DocumentFileResult result = service.loadFile(
                DOCUMENT_ID, OWNER_ID, UserRole.OWNER, "view");

        assertArrayEquals(CONTENT, result.getContent());
        assertAudit("CONTRACT_FILE_VIEW", "ALLOWED", null, VERSION_ID, OWNER_ID);
    }

    @Test
    void verifiedHealthPendingBytesAreReturnedAndPromotionIsRetried() {
        DocumentFileAccessRow row = healthRow(
                LocalDate.of(2027, 8, 11), "image/jpeg");
        when(documentAccessMapper.lockFileAccessContext(DOCUMENT_ID)).thenReturn(row);
        when(storageAdapter.exists(HEALTH_FINAL_KEY)).thenReturn(false);
        when(storageAdapter.exists(HEALTH_PENDING_KEY)).thenReturn(true);
        when(storageAdapter.read(HEALTH_PENDING_KEY)).thenReturn(CONTENT);

        DocumentFileResult result = service.loadFile(
                DOCUMENT_ID, WORKER_ID, UserRole.WORKER, "view");

        assertArrayEquals(CONTENT, result.getContent());
        verify(storageAdapter).promote(
                HEALTH_PENDING_KEY, HEALTH_FINAL_KEY, Sha256.digest(CONTENT));
        assertAudit("HEALTH_CERT_FILE_VIEW", "ALLOWED", null, VERSION_ID, WORKER_ID);
    }

    @Test
    void verifiedHealthPendingBytesRecoverFromAFinalChecksumMismatch() {
        DocumentFileAccessRow row = healthRow(
                LocalDate.of(2027, 8, 11), "image/jpeg");
        when(documentAccessMapper.lockFileAccessContext(DOCUMENT_ID)).thenReturn(row);
        when(storageAdapter.exists(HEALTH_FINAL_KEY)).thenReturn(true);
        when(storageAdapter.read(HEALTH_FINAL_KEY)).thenReturn(new byte[]{9});
        when(storageAdapter.exists(HEALTH_PENDING_KEY)).thenReturn(true);
        when(storageAdapter.read(HEALTH_PENDING_KEY)).thenReturn(CONTENT);

        DocumentFileResult result = service.loadFile(
                DOCUMENT_ID, WORKER_ID, UserRole.WORKER, "view");

        assertArrayEquals(CONTENT, result.getContent());
        verify(storageAdapter).promote(
                HEALTH_PENDING_KEY, HEALTH_FINAL_KEY, Sha256.digest(CONTENT));
        assertAudit("HEALTH_CERT_FILE_VIEW", "ALLOWED", null, VERSION_ID, WORKER_ID);
    }

    @Test
    void healthPendingChecksumMismatchFailsClosedWithoutReturningBytes() {
        DocumentFileAccessRow row = healthRow(
                LocalDate.of(2027, 8, 11), "image/jpeg");
        when(documentAccessMapper.lockFileAccessContext(DOCUMENT_ID)).thenReturn(row);
        when(storageAdapter.exists(HEALTH_FINAL_KEY)).thenReturn(false);
        when(storageAdapter.exists(HEALTH_PENDING_KEY)).thenReturn(true);
        when(storageAdapter.read(HEALTH_PENDING_KEY)).thenReturn(new byte[]{9});

        assertThrows(DocumentStorageIntegrityException.class, () -> service.loadFile(
                DOCUMENT_ID, WORKER_ID, UserRole.WORKER, "view"));

        verify(storageAdapter, never()).promote(any(), any(), any());
        assertAudit("HEALTH_CERT_FILE_VIEW", "DENIED", "CHECKSUM_MISMATCH",
                VERSION_ID, WORKER_ID);
    }

    @Test
    void missingHealthFinalAndPendingObjectsFailClosedWithoutReturningBytes() {
        DocumentFileAccessRow row = healthRow(
                LocalDate.of(2027, 8, 11), "image/jpeg");
        when(documentAccessMapper.lockFileAccessContext(DOCUMENT_ID)).thenReturn(row);
        when(storageAdapter.exists(HEALTH_FINAL_KEY)).thenReturn(false);
        when(storageAdapter.exists(HEALTH_PENDING_KEY)).thenReturn(false);

        assertThrows(DocumentStorageIntegrityException.class, () -> service.loadFile(
                DOCUMENT_ID, WORKER_ID, UserRole.WORKER, "view"));

        verify(storageAdapter, never()).read(any());
        verify(storageAdapter, never()).promote(any(), any(), any());
        assertAudit("HEALTH_CERT_FILE_VIEW", "DENIED", "FILE_UNAVAILABLE",
                VERSION_ID, WORKER_ID);
    }

    @Test
    void nonCanonicalHealthFinalObjectIsNotReadEvenWhenItsChecksumMatches() {
        DocumentFileAccessRow row = healthRow(
                LocalDate.of(2027, 8, 11),
                "image/jpeg",
                "health-certificates/4/10/unexpected/v1.jpg");
        when(documentAccessMapper.lockFileAccessContext(DOCUMENT_ID)).thenReturn(row);
        lenient().when(storageAdapter.exists(row.getStorageKey())).thenReturn(true);
        lenient().when(storageAdapter.read(row.getStorageKey())).thenReturn(CONTENT);

        assertThrows(DocumentStorageIntegrityException.class, () -> service.loadFile(
                DOCUMENT_ID, WORKER_ID, UserRole.WORKER, "view"));

        verify(storageAdapter, never()).exists(any());
        verify(storageAdapter, never()).read(any());
        assertAudit("HEALTH_CERT_FILE_VIEW", "DENIED", "FILE_UNAVAILABLE",
                VERSION_ID, WORKER_ID);
    }

    @Test
    void nonCanonicalContractFinalObjectIsNotReadEvenWhenItsChecksumMatches() {
        DocumentFileAccessRow row = contractRow(
                "ACTIVE", "application/pdf", "contracts/1/10/unexpected/v2.pdf");
        when(documentAccessMapper.lockFileAccessContext(DOCUMENT_ID)).thenReturn(row);
        lenient().when(storageAdapter.exists(row.getStorageKey())).thenReturn(true);
        lenient().when(storageAdapter.read(row.getStorageKey())).thenReturn(CONTENT);

        assertThrows(DocumentStorageIntegrityException.class, () -> service.loadFile(
                DOCUMENT_ID, OWNER_ID, UserRole.OWNER, "view"));

        verify(storageAdapter, never()).exists(any());
        verify(storageAdapter, never()).read(any());
        assertAudit("CONTRACT_FILE_VIEW", "DENIED", "FILE_UNAVAILABLE",
                VERSION_ID, OWNER_ID);
    }

    @Test
    void unsupportedContractMimeDoesNotProbeStorage() {
        DocumentFileAccessRow row = contractRow("ACTIVE", "text/html");
        when(documentAccessMapper.lockFileAccessContext(DOCUMENT_ID)).thenReturn(row);

        assertThrows(DocumentStorageIntegrityException.class, () -> service.loadFile(
                DOCUMENT_ID, OWNER_ID, UserRole.OWNER, "view"));

        verify(storageAdapter, never()).exists(any());
        verify(storageAdapter, never()).read(any());
        assertAudit("CONTRACT_FILE_VIEW", "DENIED", "FILE_UNAVAILABLE",
                VERSION_ID, OWNER_ID);
    }

    @Test
    void auditFailureFailsClosedAfterBytesAreVerified() {
        givenReadable(contractRow("ACTIVE", "application/pdf"));
        when(documentAccessMapper.insertAccessLog(any())).thenReturn(0);

        assertThrows(IllegalStateException.class, () -> service.loadFile(
                DOCUMENT_ID, OWNER_ID, UserRole.OWNER, "view"));
    }

    private void givenReadable(DocumentFileAccessRow row) {
        when(documentAccessMapper.lockFileAccessContext(DOCUMENT_ID)).thenReturn(row);
        when(storageAdapter.exists(row.getStorageKey())).thenReturn(true);
        when(storageAdapter.read(row.getStorageKey())).thenReturn(CONTENT);
    }

    private DocumentFileAccessRow contractRow(String status, String mimeType) {
        return contractRow(status, mimeType, CONTRACT_FINAL_KEY);
    }

    private DocumentFileAccessRow contractRow(
            String status,
            String mimeType,
            String storageKey) {
        return DocumentFileAccessRow.builder()
                .documentId(DOCUMENT_ID)
                .ownerUserId(OWNER_ID)
                .workCaseId(WORK_CASE_ID)
                .docType("EMPLOYMENT_CONTRACT")
                .status(status)
                .issuedDate(LocalDate.of(2026, 8, 11))
                .versionId(VERSION_ID)
                .versionNo(2)
                .versionType("SIGNED")
                .storageKey(storageKey)
                .mimeType(mimeType)
                .checksum(Sha256.digest(CONTENT))
                .contractOwnerUserId(OWNER_ID)
                .contractWorkerUserId(WORKER_ID)
                .ownerName("김사장")
                .workerName("김근로")
                .workplaceId(8L)
                .workplaceName("강남점")
                .build();
    }

    private DocumentFileAccessRow contractRowWithoutVersion() {
        return DocumentFileAccessRow.builder()
                .documentId(DOCUMENT_ID)
                .ownerUserId(OWNER_ID)
                .workCaseId(WORK_CASE_ID)
                .docType("EMPLOYMENT_CONTRACT")
                .status("ACTIVE")
                .contractOwnerUserId(OWNER_ID)
                .contractWorkerUserId(WORKER_ID)
                .build();
    }

    private DocumentFileAccessRow healthRow(LocalDate expiresDate, String mimeType) {
        String extension = switch (mimeType) {
            case "image/jpeg" -> "jpg";
            case "image/png" -> "png";
            case "application/pdf" -> "pdf";
            default -> "bin";
        };
        return healthRow(
                expiresDate,
                mimeType,
                "health-certificates/4/10/v1." + extension);
    }

    private DocumentFileAccessRow healthRow(
            LocalDate expiresDate,
            String mimeType,
            String storageKey) {
        return DocumentFileAccessRow.builder()
                .documentId(DOCUMENT_ID)
                .ownerUserId(WORKER_ID)
                .docType("HEALTH_CERTIFICATE")
                .status("ACTIVE")
                .issuedDate(LocalDate.of(2026, 8, 1))
                .expiresDate(expiresDate)
                .versionId(VERSION_ID)
                .versionNo(1)
                .versionType("ORIGINAL")
                .storageKey(storageKey)
                .mimeType(mimeType)
                .checksum(Sha256.digest(CONTENT))
                .ownerName("김근로")
                .build();
    }

    private void assertAudit(
            String action,
            String result,
            String reason,
            Long versionId,
            Long actorUserId) {
        ArgumentCaptor<DocumentAccessLogParam> captor =
                ArgumentCaptor.forClass(DocumentAccessLogParam.class);
        verify(documentAccessMapper).insertAccessLog(captor.capture());
        DocumentAccessLogParam audit = captor.getValue();
        assertEquals(action, audit.getAction());
        assertEquals(result, audit.getResult());
        assertEquals(reason, audit.getDenialReason());
        assertEquals(versionId, audit.getDocumentVersionId());
        assertEquals(actorUserId, audit.getActorUserId());
    }
}
