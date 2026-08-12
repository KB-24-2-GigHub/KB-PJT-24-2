package com.gighub.document.service;

import com.gighub.document.dto.DocumentDetailResponse;
import com.gighub.document.exception.DocumentNotFoundException;
import com.gighub.document.mapper.DocumentAccessMapper;
import com.gighub.document.mapper.param.DocumentAccessLogParam;
import com.gighub.document.mapper.result.DocumentFileAccessRow;
import com.gighub.document.mapper.result.DocumentHealthShareAccessRow;
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
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DocumentDetailAccessTransactionTest {

    private static final long DOCUMENT_ID = 10L;
    private static final long OWNER_ID = 4L;
    private static final long WORKER_ID = 7L;
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 12, 12, 0);

    @Mock
    private DocumentAccessMapper mapper;

    private DocumentDetailAccessTransaction transaction;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(
                Instant.parse("2026-08-12T03:00:00Z"),
                ZoneId.of("Asia/Seoul"));
        transaction = new DocumentDetailAccessTransaction(mapper, clock);
    }

    @Test
    void contractWorkerGetsSharedDetailWithOnlyTheSignedVersion() {
        when(mapper.lockFileAccessContext(DOCUMENT_ID)).thenReturn(contractRow());
        when(mapper.insertAccessLog(org.mockito.ArgumentMatchers.any())).thenReturn(1);

        DocumentDetailResponse result = transaction.loadDetail(
                DOCUMENT_ID, WORKER_ID, UserRole.WORKER, null);

        assertEquals("SHARED", result.getItem().getSource());
        assertEquals(201L, result.getItem().getWorkCaseId());
        assertEquals("김사장", result.getItem().getSharedByName());
        assertEquals(1, result.getVersions().size());
        assertEquals("SIGNED", result.getVersions().get(0).getVersionType());
        assertEquals(2, result.getVersions().get(0).getVersionNo());
        assertAudit("ALLOWED", null, 50L, WORKER_ID);
    }

    @Test
    void expiredHealthOwnerKeepsOwnDetailButCannotShareIt() {
        DocumentFileAccessRow row = healthRow(
                LocalDate.of(2026, 8, 11), true);
        when(mapper.lockFileAccessContext(DOCUMENT_ID)).thenReturn(row);
        when(mapper.hasShareableHealthWorkCase(
                DOCUMENT_ID, WORKER_ID, NOW, NOW.toLocalDate())).thenReturn(false);
        when(mapper.insertAccessLog(org.mockito.ArgumentMatchers.any())).thenReturn(1);

        DocumentDetailResponse result = transaction.loadDetail(
                DOCUMENT_ID, WORKER_ID, UserRole.WORKER, null);

        assertEquals("OWN", result.getItem().getSource());
        assertEquals("EXPIRED", result.getItem().getStatus());
        assertFalse(result.getItem().getCapabilities().isCanShare());
        assertTrue(result.getItem().getCapabilities().isCanDelete());
        assertAudit("ALLOWED", null, 60L, WORKER_ID);
    }

    @Test
    void ownerWithOneValidHealthShareGetsThatWorkCaseProjection() {
        DocumentHealthShareAccessRow share = DocumentHealthShareAccessRow.builder()
                .shareId(91L)
                .workCaseId(201L)
                .workplaceId(3L)
                .workplaceName("강남점")
                .build();
        when(mapper.lockFileAccessContext(DOCUMENT_ID)).thenReturn(healthRow());
        when(mapper.lockValidHealthShareContexts(
                DOCUMENT_ID, OWNER_ID, 201L, NOW, NOW.toLocalDate()))
                .thenReturn(List.of(share));
        when(mapper.insertAccessLog(org.mockito.ArgumentMatchers.any())).thenReturn(1);

        DocumentDetailResponse result = transaction.loadDetail(
                DOCUMENT_ID, OWNER_ID, UserRole.OWNER, 201L);

        assertEquals("SHARED", result.getItem().getSource());
        assertEquals(201L, result.getItem().getWorkCaseId());
        assertEquals(3L, result.getItem().getWorkplaceId());
        assertFalse(result.getItem().getCapabilities().isCanShare());
        assertFalse(result.getItem().getCapabilities().isCanDelete());
        assertAudit("ALLOWED", null, 60L, OWNER_ID);
    }

    @Test
    void selectedSharedWorkCaseDoesNotFallbackToAnotherValidRelationship() {
        DocumentHealthShareAccessRow first = DocumentHealthShareAccessRow.builder()
                .shareId(91L)
                .workCaseId(201L)
                .workplaceId(3L)
                .workplaceName("강남점")
                .build();
        when(mapper.lockFileAccessContext(DOCUMENT_ID)).thenReturn(healthRow());
        when(mapper.lockValidHealthShareContexts(
                DOCUMENT_ID, OWNER_ID, 201L, NOW, NOW.toLocalDate()))
                .thenReturn(List.of(first));
        when(mapper.insertAccessLog(org.mockito.ArgumentMatchers.any())).thenReturn(1);

        DocumentDetailResponse result = transaction.loadDetail(
                DOCUMENT_ID, OWNER_ID, UserRole.OWNER, 201L);

        assertEquals(201L, result.getItem().getWorkCaseId());
        assertEquals(3L, result.getItem().getWorkplaceId());
        assertAudit("ALLOWED", null, 60L, OWNER_ID);
    }

    @Test
    void sharedHealthRequestWithoutWorkCaseIdNeverAutoSelects() {
        when(mapper.lockFileAccessContext(DOCUMENT_ID)).thenReturn(healthRow());
        when(mapper.insertAccessLog(org.mockito.ArgumentMatchers.any())).thenReturn(1);

        assertThrows(DocumentNotFoundException.class,
                () -> transaction.loadDetail(
                        DOCUMENT_ID, OWNER_ID, UserRole.OWNER, null));

        assertAudit("DENIED", "PARTY_ACCESS_DENIED", 60L, OWNER_ID);
        verify(mapper, never()).lockValidHealthShareContexts(
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void unrelatedWorkCaseDoesNotFallbackAndGetsPartyDeniedAudit() {
        when(mapper.lockFileAccessContext(DOCUMENT_ID)).thenReturn(healthRow());
        when(mapper.lockValidHealthShareContexts(
                DOCUMENT_ID, OWNER_ID, 999L, NOW, NOW.toLocalDate()))
                .thenReturn(List.of());
        when(mapper.hasHealthShareHistoryForWorkCase(
                DOCUMENT_ID, OWNER_ID, 999L)).thenReturn(false);
        when(mapper.insertAccessLog(org.mockito.ArgumentMatchers.any())).thenReturn(1);

        assertThrows(DocumentNotFoundException.class,
                () -> transaction.loadDetail(
                        DOCUMENT_ID, OWNER_ID, UserRole.OWNER, 999L));

        assertAudit("DENIED", "PARTY_ACCESS_DENIED", 60L, OWNER_ID);
    }

    @Test
    void revokedSelectedWorkCaseGetsUnavailableAudit() {
        when(mapper.lockFileAccessContext(DOCUMENT_ID)).thenReturn(healthRow());
        when(mapper.lockValidHealthShareContexts(
                DOCUMENT_ID, OWNER_ID, 201L, NOW, NOW.toLocalDate()))
                .thenReturn(List.of());
        when(mapper.hasHealthShareHistoryForWorkCase(
                DOCUMENT_ID, OWNER_ID, 201L)).thenReturn(true);
        when(mapper.insertAccessLog(org.mockito.ArgumentMatchers.any())).thenReturn(1);

        assertThrows(DocumentNotFoundException.class,
                () -> transaction.loadDetail(
                        DOCUMENT_ID, OWNER_ID, UserRole.OWNER, 201L));

        assertAudit("DENIED", "DOCUMENT_UNAVAILABLE", 60L, OWNER_ID);
    }

    @Test
    void missingAllowedVersionIsAuditedWithNullVersionAndHidden() {
        DocumentFileAccessRow row = healthRow(
                LocalDate.of(2027, 8, 1), false);
        when(mapper.lockFileAccessContext(DOCUMENT_ID)).thenReturn(row);
        when(mapper.insertAccessLog(org.mockito.ArgumentMatchers.any())).thenReturn(1);

        assertThrows(DocumentNotFoundException.class,
                () -> transaction.loadDetail(
                        DOCUMENT_ID, WORKER_ID, UserRole.WORKER, null));

        assertAudit("DENIED", "DOCUMENT_UNAVAILABLE", null, WORKER_ID);
    }

    @Test
    void missingDocumentCreatesNoForeignKeyAuditRow() {
        when(mapper.lockFileAccessContext(DOCUMENT_ID)).thenReturn(null);

        assertThrows(DocumentNotFoundException.class,
                () -> transaction.loadDetail(
                        DOCUMENT_ID, WORKER_ID, UserRole.WORKER, null));

        verify(mapper, never()).insertAccessLog(
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void auditFailureFailsClosedBeforeResponseIsReturned() {
        when(mapper.lockFileAccessContext(DOCUMENT_ID)).thenReturn(contractRow());
        when(mapper.insertAccessLog(org.mockito.ArgumentMatchers.any())).thenReturn(0);

        assertThrows(IllegalStateException.class,
                () -> transaction.loadDetail(
                        DOCUMENT_ID, WORKER_ID, UserRole.WORKER, null));
    }

    private void assertAudit(
            String result,
            String reason,
            Long versionId,
            long actorUserId) {
        ArgumentCaptor<DocumentAccessLogParam> captor =
                ArgumentCaptor.forClass(DocumentAccessLogParam.class);
        verify(mapper).insertAccessLog(captor.capture());
        DocumentAccessLogParam audit = captor.getValue();
        assertEquals(DOCUMENT_ID, audit.getDocumentId());
        assertEquals(versionId, audit.getDocumentVersionId());
        assertEquals(actorUserId, audit.getActorUserId());
        assertEquals("DOCUMENT_DETAIL_VIEW", audit.getAction());
        assertEquals(result, audit.getResult());
        assertEquals(reason, audit.getDenialReason());
    }

    private DocumentFileAccessRow contractRow() {
        return DocumentFileAccessRow.builder()
                .documentId(DOCUMENT_ID)
                .ownerUserId(OWNER_ID)
                .workCaseId(201L)
                .docType("EMPLOYMENT_CONTRACT")
                .status("ACTIVE")
                .issuedDate(LocalDate.of(2026, 8, 10))
                .versionId(50L)
                .versionNo(2)
                .versionType("SIGNED")
                .storageKey("contracts/201/10/v2.pdf")
                .mimeType("application/pdf")
                .sizeBytes(1_024L)
                .checksum(new byte[32])
                .versionCreatedAt(NOW)
                .documentCreatedAt(NOW)
                .contractOwnerUserId(OWNER_ID)
                .contractWorkerUserId(WORKER_ID)
                .ownerName("김사장")
                .workerName("김근로")
                .workplaceId(3L)
                .workplaceName("강남점")
                .build();
    }

    private DocumentFileAccessRow healthRow() {
        return healthRow(LocalDate.of(2027, 8, 1), true);
    }

    private DocumentFileAccessRow healthRow(
            LocalDate expiresDate,
            boolean includeVersion) {
        return DocumentFileAccessRow.builder()
                .documentId(DOCUMENT_ID)
                .ownerUserId(WORKER_ID)
                .docType("HEALTH_CERTIFICATE")
                .status("ACTIVE")
                .issuedDate(LocalDate.of(2026, 8, 1))
                .expiresDate(expiresDate)
                .versionId(includeVersion ? 60L : null)
                .versionNo(includeVersion ? 1 : null)
                .versionType(includeVersion ? "ORIGINAL" : null)
                .storageKey("health-certificates/7/10/v1.jpg")
                .mimeType(includeVersion ? "image/jpeg" : null)
                .sizeBytes(includeVersion ? 512L : null)
                .checksum(new byte[32])
                .versionCreatedAt(includeVersion ? NOW : null)
                .documentCreatedAt(NOW)
                .ownerName("김근로")
                .build();
    }
}
