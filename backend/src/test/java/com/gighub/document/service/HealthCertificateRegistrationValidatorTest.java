package com.gighub.document.service;

import com.gighub.auth.security.AuthPrincipal;
import com.gighub.common.exception.RoleMismatchException;
import com.gighub.common.exception.ValidationException;
import com.gighub.document.validation.HealthCertificateFileValidator;
import com.gighub.member.domain.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class HealthCertificateRegistrationValidatorTest {

    private static final byte[] JPEG_BYTES =
            new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0x01};
    private static final AuthPrincipal WORKER =
            new AuthPrincipal(1L, UserRole.WORKER, "이알바");
    private static final AuthPrincipal OWNER =
            new AuthPrincipal(2L, UserRole.OWNER, "김사장");

    private HealthCertificateRegistrationValidator validator;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(Instant.parse("2026-08-14T03:00:00Z"), ZoneId.of("Asia/Seoul"));
        validator = new HealthCertificateRegistrationValidator(
                new HealthCertificateFileValidator(), clock);
    }

    @Test
    void acceptsAWorkersHealthCertificateRegistrationOnTheServerDate() {
        LocalDate issuedDate = LocalDate.of(2026, 8, 14);
        MockMultipartFile file = jpegFile();

        ValidatedHealthCertificateRegistration result =
                validator.validate(WORKER, "HEALTH_CERTIFICATE", issuedDate, file);

        assertEquals(1L, result.ownerUserId());
        assertEquals(issuedDate, result.issuedDate());
        assertEquals("jpg", result.file().storageExtension());
    }

    @Test
    void rejectsAnOwnerRegisteringAHealthCertificate() {
        assertThrows(RoleMismatchException.class, () -> validator.validate(
                OWNER, "HEALTH_CERTIFICATE", LocalDate.of(2026, 8, 14), jpegFile()));
    }

    @Test
    void rejectsADocTypeOtherThanHealthCertificate() {
        ValidationException exception = assertThrows(ValidationException.class, () -> validator.validate(
                WORKER, "EMPLOYMENT_CONTRACT", LocalDate.of(2026, 8, 14), jpegFile()));

        assertEquals("docType", exception.getFieldErrors().get(0).getField());
    }

    @Test
    void rejectsAFutureIssuedDate() {
        ValidationException exception = assertThrows(ValidationException.class, () -> validator.validate(
                WORKER, "HEALTH_CERTIFICATE", LocalDate.of(2026, 8, 15), jpegFile()));

        assertEquals("issuedDate", exception.getFieldErrors().get(0).getField());
        assertEquals("FUTURE_DATE", exception.getFieldErrors().get(0).getReason());
    }

    @Test
    void rejectsAMissingIssuedDate() {
        ValidationException exception = assertThrows(ValidationException.class, () -> validator.validate(
                WORKER, "HEALTH_CERTIFICATE", null, jpegFile()));

        assertEquals("REQUIRED", exception.getFieldErrors().get(0).getReason());
    }

    private MockMultipartFile jpegFile() {
        return new MockMultipartFile("file", "photo.jpg", "image/jpeg", JPEG_BYTES);
    }
}
