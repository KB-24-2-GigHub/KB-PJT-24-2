package com.gighub.document.validation;

import com.gighub.common.exception.ValidationException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.security.MessageDigest;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class HealthCertificateFileValidatorTest {

    private static final byte[] JPEG_BYTES =
            new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0x01, 0x02, 0x03};
    private static final byte[] PNG_BYTES = new byte[]{
            (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x01};
    private static final byte[] PDF_BYTES =
            new byte[]{0x25, 0x50, 0x44, 0x46, 0x2D, 0x31, 0x2E, 0x34};

    private final HealthCertificateFileValidator validator = new HealthCertificateFileValidator();

    @Test
    void acceptsAJpegFileAndComputesItsSha256Checksum() throws Exception {
        MockMultipartFile file =
                new MockMultipartFile("file", "photo.jpg", "image/jpeg", JPEG_BYTES);

        ValidatedHealthCertificateFile result = validator.validate(file);

        assertEquals("jpg", result.storageExtension());
        assertEquals("image/jpeg", result.mimeType());
        assertArrayEquals(JPEG_BYTES, result.content());
        assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(JPEG_BYTES), result.checksum());
    }

    @Test
    void acceptsAPngFile() {
        MockMultipartFile file =
                new MockMultipartFile("file", "photo.png", "image/png", PNG_BYTES);

        ValidatedHealthCertificateFile result = validator.validate(file);

        assertEquals("png", result.storageExtension());
        assertEquals("image/png", result.mimeType());
    }

    @Test
    void acceptsAPdfFile() {
        MockMultipartFile file =
                new MockMultipartFile("file", "cert.pdf", "application/pdf", PDF_BYTES);

        ValidatedHealthCertificateFile result = validator.validate(file);

        assertEquals("pdf", result.storageExtension());
        assertEquals("application/pdf", result.mimeType());
    }

    @Test
    void rejectsAMissingFile() {
        ValidationException exception =
                assertThrows(ValidationException.class, () -> validator.validate(null));

        assertEquals("file", exception.getFieldErrors().get(0).getField());
    }

    @Test
    void rejectsAFileLargerThan10Mebibytes() {
        byte[] oversized = Arrays.copyOf(JPEG_BYTES, 10 * 1024 * 1024 + 1);
        System.arraycopy(JPEG_BYTES, 0, oversized, 0, JPEG_BYTES.length);
        MockMultipartFile file =
                new MockMultipartFile("file", "photo.jpg", "image/jpeg", oversized);

        ValidationException exception =
                assertThrows(ValidationException.class, () -> validator.validate(file));

        assertEquals("SIZE_EXCEEDED", exception.getFieldErrors().get(0).getReason());
    }

    @Test
    void rejectsAnUnsupportedExtension() {
        MockMultipartFile file =
                new MockMultipartFile("file", "cert.gif", "image/gif", JPEG_BYTES);

        ValidationException exception =
                assertThrows(ValidationException.class, () -> validator.validate(file));

        assertEquals("UNSUPPORTED_TYPE", exception.getFieldErrors().get(0).getReason());
    }

    @Test
    void rejectsADeclaredMimeTypeThatDoesNotMatchTheExtension() {
        MockMultipartFile file =
                new MockMultipartFile("file", "photo.jpg", "image/png", JPEG_BYTES);

        ValidationException exception =
                assertThrows(ValidationException.class, () -> validator.validate(file));

        assertEquals("MIME_MISMATCH", exception.getFieldErrors().get(0).getReason());
    }

    @Test
    void rejectsAFileWhoseContentSignatureDoesNotMatchItsDeclaredExtensionAndMimeType() {
        MockMultipartFile file =
                new MockMultipartFile("file", "photo.jpg", "image/jpeg", PNG_BYTES);

        ValidationException exception =
                assertThrows(ValidationException.class, () -> validator.validate(file));

        assertEquals("SIGNATURE_MISMATCH", exception.getFieldErrors().get(0).getReason());
    }
}
