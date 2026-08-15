package com.gighub.document.service;

import com.gighub.auth.security.AuthPrincipal;
import com.gighub.document.dto.DocumentListItem;
import com.gighub.document.storage.DocumentStorageAdapter;
import com.gighub.document.validation.UploadedFile;
import com.gighub.document.validation.ValidatedHealthCertificateFile;
import com.gighub.member.domain.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HealthCertificateRegisterServiceImplTest {

    private static final AuthPrincipal WORKER = new AuthPrincipal(1L, UserRole.WORKER, "이알바");
    private static final byte[] CONTENT = new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};

    @Mock
    private HealthCertificateRegistrationValidator validator;

    @Mock
    private HealthCertificateRegisterTransaction transaction;

    @Mock
    private DocumentStorageAdapter storageAdapter;

    private HealthCertificateRegisterServiceImpl service;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(Instant.parse("2026-08-14T03:00:00Z"), ZoneId.of("Asia/Seoul"));
        service = new HealthCertificateRegisterServiceImpl(
                validator, transaction, storageAdapter, clock);
    }

    @Test
    void registersAndPromotesAFreshlyIssuedHealthCertificate() {
        LocalDate issuedDate = LocalDate.of(2026, 8, 14);
        ValidatedHealthCertificateRegistration validated = new ValidatedHealthCertificateRegistration(
                1L, issuedDate, new ValidatedHealthCertificateFile(CONTENT, "jpg", "image/jpeg", new byte[]{1}));
        when(validator.validate(any(), any(), any(), any())).thenReturn(validated);
        HealthCertificateRegistrationHandle handle = new HealthCertificateRegistrationHandle(
                9L, "pending/key", "final/key", new byte[]{1}, LocalDate.of(2027, 8, 14));
        when(transaction.register(validated)).thenReturn(handle);

        DocumentListItem result = service.register(
                WORKER, "HEALTH_CERTIFICATE", issuedDate,
                new UploadedFile(CONTENT, "photo.jpg", "image/jpeg"));

        assertEquals(9L, result.getDocumentId());
        assertEquals("HEALTH_CERTIFICATE", result.getDocType());
        assertEquals("ACTIVE", result.getStatus());
        assertEquals(issuedDate, result.getIssuedDate());
        assertEquals(LocalDate.of(2027, 8, 14), result.getExpiresDate());
        assertEquals(1, result.getLatestVersion());
        assertFalse(result.getCapabilities().isCanShare());
        assertTrue(result.getCapabilities().isCanDelete());

        verify(storageAdapter).promote("pending/key", "final/key", new byte[]{1});
    }

    @Test
    void marksAnAlreadyExpiredRegistrationAsExpiredWithoutFailingTheRequest() {
        LocalDate issuedDate = LocalDate.of(2020, 1, 1);
        ValidatedHealthCertificateRegistration validated = new ValidatedHealthCertificateRegistration(
                1L, issuedDate, new ValidatedHealthCertificateFile(CONTENT, "jpg", "image/jpeg", new byte[]{1}));
        when(validator.validate(any(), any(), any(), any())).thenReturn(validated);
        HealthCertificateRegistrationHandle handle = new HealthCertificateRegistrationHandle(
                9L, "pending/key", "final/key", new byte[]{1}, LocalDate.of(2021, 1, 1));
        when(transaction.register(validated)).thenReturn(handle);

        DocumentListItem result = service.register(
                WORKER, "HEALTH_CERTIFICATE", issuedDate,
                new UploadedFile(CONTENT, "photo.jpg", "image/jpeg"));

        assertEquals("EXPIRED", result.getStatus());
    }

    @Test
    void doesNotFailTheRequestWhenPostCommitPromotionFails() {
        LocalDate issuedDate = LocalDate.of(2026, 8, 14);
        ValidatedHealthCertificateRegistration validated = new ValidatedHealthCertificateRegistration(
                1L, issuedDate, new ValidatedHealthCertificateFile(CONTENT, "jpg", "image/jpeg", new byte[]{1}));
        when(validator.validate(any(), any(), any(), any())).thenReturn(validated);
        HealthCertificateRegistrationHandle handle = new HealthCertificateRegistrationHandle(
                9L, "pending/key", "final/key", new byte[]{1}, LocalDate.of(2027, 8, 14));
        when(transaction.register(validated)).thenReturn(handle);
        doThrow(new RuntimeException("storage unavailable"))
                .when(storageAdapter).promote(any(), any(), any());

        DocumentListItem result = service.register(
                WORKER, "HEALTH_CERTIFICATE", issuedDate,
                new UploadedFile(CONTENT, "photo.jpg", "image/jpeg"));

        assertEquals(9L, result.getDocumentId());
    }
}
