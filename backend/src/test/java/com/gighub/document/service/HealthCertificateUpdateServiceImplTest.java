package com.gighub.document.service;

import com.gighub.auth.security.AuthPrincipal;
import com.gighub.common.exception.RoleMismatchException;
import com.gighub.common.exception.ValidationException;
import com.gighub.document.dto.DocumentListItem;
import com.gighub.document.exception.DocumentNotFoundException;
import com.gighub.document.mapper.ContractDocumentWriteMapper;
import com.gighub.document.mapper.DocumentQueryMapper;
import com.gighub.document.mapper.result.DocumentListRow;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HealthCertificateUpdateServiceImplTest {

    private static final long DOCUMENT_ID = 9L;
    private static final AuthPrincipal WORKER = new AuthPrincipal(1L, UserRole.WORKER, "이알바");
    private static final AuthPrincipal OWNER = new AuthPrincipal(2L, UserRole.OWNER, "김사장");

    @Mock
    private ContractDocumentWriteMapper documentMapper;

    @Mock
    private DocumentQueryMapper documentQueryMapper;

    private HealthCertificateUpdateServiceImpl service;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(Instant.parse("2026-08-14T03:00:00Z"), ZoneId.of("Asia/Seoul"));
        service = new HealthCertificateUpdateServiceImpl(documentMapper, documentQueryMapper, clock);
    }

    @Test
    void updatesTheIssuedDateAndReturnsTheReloadedItem() {
        LocalDate issuedDate = LocalDate.of(2026, 8, 14);
        when(documentMapper.updateHealthCertificateIssuedDate(
                DOCUMENT_ID, 1L, issuedDate, LocalDate.of(2027, 8, 14)))
                .thenReturn(1);
        DocumentListRow row = new DocumentListRow(
                DOCUMENT_ID, "HEALTH_CERTIFICATE", "ACTIVE", "image/jpeg",
                issuedDate, LocalDate.of(2027, 8, 14), 1, "OWN", "이알바", null,
                null, null, null, null, false, LocalDateTime.of(2026, 8, 14, 12, 0));
        when(documentQueryMapper.findOwnHealthCertificateById(1L, DOCUMENT_ID, LocalDate.of(2026, 8, 14)))
                .thenReturn(row);

        DocumentListItem result = service.updateIssuedDate(WORKER, DOCUMENT_ID, issuedDate);

        assertEquals(DOCUMENT_ID, result.getDocumentId());
        assertEquals(issuedDate, result.getIssuedDate());
    }

    @Test
    void rejectsAnOwnerUpdatingAHealthCertificate() {
        assertThrows(RoleMismatchException.class, () -> service.updateIssuedDate(
                OWNER, DOCUMENT_ID, LocalDate.of(2026, 8, 14)));

        verify(documentMapper, never()).updateHealthCertificateIssuedDate(
                anyLong(), anyLong(), any(), any());
    }

    @Test
    void rejectsAFutureIssuedDate() {
        ValidationException exception = assertThrows(ValidationException.class, () -> service.updateIssuedDate(
                WORKER, DOCUMENT_ID, LocalDate.of(2026, 8, 15)));

        assertEquals("FUTURE_DATE", exception.getFieldErrors().get(0).getReason());
        verify(documentMapper, never()).updateHealthCertificateIssuedDate(
                anyLong(), anyLong(), any(), any());
    }

    @Test
    void throwsNotFoundWhenTheDocumentIsMissingNotOwnedOrNotAnActiveHealthCertificate() {
        when(documentMapper.updateHealthCertificateIssuedDate(anyLong(), anyLong(), any(), any()))
                .thenReturn(0);

        assertThrows(DocumentNotFoundException.class, () -> service.updateIssuedDate(
                WORKER, DOCUMENT_ID, LocalDate.of(2026, 8, 14)));

        verify(documentQueryMapper, never()).findOwnHealthCertificateById(any(), any(), any());
    }
}
