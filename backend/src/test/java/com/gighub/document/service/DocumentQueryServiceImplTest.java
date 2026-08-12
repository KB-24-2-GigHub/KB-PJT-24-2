package com.gighub.document.service;

import com.gighub.common.api.PageResponse;
import com.gighub.common.exception.ValidationException;
import com.gighub.document.dto.DocumentDetailResponse;
import com.gighub.document.dto.DocumentListItem;
import com.gighub.document.dto.DocumentShareListResponse;
import com.gighub.document.exception.DocumentNotFoundException;
import com.gighub.document.mapper.DocumentQueryMapper;
import com.gighub.document.mapper.result.DocumentListRow;
import com.gighub.document.mapper.result.DocumentShareRow;
import com.gighub.member.domain.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DocumentQueryServiceImplTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 11, 12, 0);

    @Mock
    private DocumentQueryMapper mapper;

    @Mock
    private DocumentDetailAccessTransaction detailAccessTransaction;

    private DocumentQueryServiceImpl service;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(
                Instant.parse("2026-08-11T03:00:00Z"),
                ZoneId.of("Asia/Seoul"));
        service = new DocumentQueryServiceImpl(mapper, detailAccessTransaction, clock);
    }

    @Test
    void delegatesDetailToTheAuditedTransactionBoundary() {
        DocumentDetailResponse response = DocumentDetailResponse.of(
                DocumentListItem.of(
                        10L,
                        "HEALTH_CERTIFICATE",
                        "ACTIVE",
                        "image/jpeg",
                        LocalDate.of(2026, 8, 1),
                        LocalDate.of(2027, 8, 1),
                        1,
                        "OWN",
                        "김근로",
                        null,
                        null,
                        null,
                        null,
                        null,
                        false,
                        NOW),
                List.of());
        when(detailAccessTransaction.loadDetail(10L, 4L, UserRole.WORKER, 201L))
                .thenReturn(response);

        assertEquals(response, service.findDocument(
                4L, UserRole.WORKER, 10L, 201L));
        verify(detailAccessTransaction).loadDetail(
                10L, 4L, UserRole.WORKER, 201L);
    }

    @Test
    void passesPrincipalRoleAndApprovedFiltersToTheVisibilityQuery() {
        DocumentListRow row = DocumentListRow.builder()
                .documentId(10L)
                .docType("HEALTH_CERTIFICATE")
                .status("ACTIVE")
                .mimeType("image/jpeg")
                .issuedDate(LocalDate.of(2026, 8, 1))
                .expiresDate(LocalDate.of(2027, 8, 1))
                .latestVersion(1)
                .source("OWN")
                .ownerName("김근로")
                .canShare(false)
                .createdAt(NOW)
                .build();
        when(mapper.findDocuments(
                4L, "WORKER", null, "HEALTH_CERTIFICATE", NOW, NOW.toLocalDate(), 20L, 10))
                .thenReturn(List.of(row));
        when(mapper.countDocuments(
                4L, "WORKER", null, "HEALTH_CERTIFICATE", NOW, NOW.toLocalDate()))
                .thenReturn(21L);

        PageResponse<DocumentListItem> result = service.findDocuments(
                4L, UserRole.WORKER, null, "HEALTH_CERTIFICATE", 2, 10);

        assertEquals(1, result.getContent().size());
        assertEquals(10L, result.getContent().get(0).getDocumentId());
        assertEquals(21L, result.getPage().getTotalElements());
    }

    @Test
    void rejectsUnknownFiltersBeforeQueryingTheMapper() {
        assertThrows(ValidationException.class,
                () -> service.findDocuments(
                        4L, UserRole.WORKER, -1L, null, 0, 20));
        assertThrows(ValidationException.class,
                () -> service.findDocuments(
                        4L, UserRole.WORKER, null, "CONTRACT", 0, 20));

        verify(mapper, never()).findDocuments(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void hidesShareHistoryFromEveryoneExceptTheHealthDocumentOwner() {
        when(mapper.isOwnedActiveHealthDocument(10L, 3L)).thenReturn(false);

        assertThrows(DocumentNotFoundException.class,
                () -> service.findShares(3L, 10L));

        verify(mapper, never()).findSharesByDocumentId(
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void mapsOnlyTheSafeOwnerShareProjection() {
        when(mapper.isOwnedActiveHealthDocument(10L, 4L)).thenReturn(true);
        when(mapper.findSharesByDocumentId(10L, NOW, NOW.toLocalDate()))
                .thenReturn(List.of(DocumentShareRow.builder()
                        .shareId(91L)
                        .workplaceId(3L)
                        .workplaceName("강남점")
                        .workCaseId(201L)
                        .status("ACTIVE")
                        .sharedAt(NOW)
                        .effectiveUntil(LocalDateTime.of(2026, 8, 20, 18, 0))
                        .build()));

        DocumentShareListResponse result = service.findShares(4L, 10L);

        assertEquals(1, result.getItems().size());
        assertEquals(91L, result.getItems().get(0).getShareId());
        assertEquals(3L, result.getItems().get(0).getWorkplaceId());
    }
}
