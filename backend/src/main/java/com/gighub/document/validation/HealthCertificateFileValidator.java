package com.gighub.document.validation;

import com.gighub.common.exception.ValidationException;
import com.gighub.document.storage.Sha256;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Locale;
import java.util.Map;

/**
 * 보건증 업로드 파일이 DEC-HEALTH-CERTIFICATE-LIFECYCLE·DOC-010 결정값을 만족하는지
 * 검증합니다: 정확히 10 MiB 이하 JPG·PNG·PDF이며 확장자·선언 MIME·파일 Signature가 모두
 * 일치해야 하고 SHA-256 Checksum을 계산합니다. 저장 확장자·MIME은 검증된 실제 내용을
 * 기준으로 서버가 정하며 클라이언트가 보낸 원본 값을 신뢰하지 않습니다.
 */
@Component
public class HealthCertificateFileValidator {

    private static final long MAX_SIZE_BYTES = 10L * 1024 * 1024;

    private static final Map<String, SignedType> EXTENSION_TYPES = Map.of(
            "jpg", SignedType.JPEG,
            "jpeg", SignedType.JPEG,
            "png", SignedType.PNG,
            "pdf", SignedType.PDF);

    public ValidatedHealthCertificateFile validate(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ValidationException("보건증 파일이 필요합니다.", "file", "REQUIRED");
        }
        if (file.getSize() > MAX_SIZE_BYTES) {
            throw new ValidationException("보건증 파일은 10 MiB 이하만 허용합니다.", "file", "SIZE_EXCEEDED");
        }

        SignedType expectedType = EXTENSION_TYPES.get(extractExtension(file.getOriginalFilename()));
        if (expectedType == null) {
            throw new ValidationException("보건증은 JPG, PNG, PDF만 허용합니다.", "file", "UNSUPPORTED_TYPE");
        }
        if (!expectedType.mimeType.equalsIgnoreCase(file.getContentType())) {
            throw new ValidationException("파일 형식이 확장자와 일치하지 않습니다.", "file", "MIME_MISMATCH");
        }

        byte[] content = readContent(file);
        if (!expectedType.matchesSignature(content)) {
            throw new ValidationException("파일 내용이 확장자와 일치하지 않습니다.", "file", "SIGNATURE_MISMATCH");
        }

        return new ValidatedHealthCertificateFile(
                content, expectedType.storageExtension, expectedType.mimeType, Sha256.digest(content));
    }

    private String extractExtension(String originalFilename) {
        if (originalFilename == null) {
            return "";
        }
        int dotIndex = originalFilename.lastIndexOf('.');
        if (dotIndex < 0 || dotIndex == originalFilename.length() - 1) {
            return "";
        }
        return originalFilename.substring(dotIndex + 1).toLowerCase(Locale.ROOT);
    }

    private byte[] readContent(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw new ValidationException("보건증 파일을 읽을 수 없습니다.", "file", "UNREADABLE");
        }
    }

    private enum SignedType {
        JPEG("jpg", "image/jpeg", new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF}),
        PNG("png", "image/png", new byte[]{
                (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A}),
        PDF("pdf", "application/pdf", new byte[]{0x25, 0x50, 0x44, 0x46, 0x2D});

        private final String storageExtension;
        private final String mimeType;
        private final byte[] signature;

        SignedType(String storageExtension, String mimeType, byte[] signature) {
            this.storageExtension = storageExtension;
            this.mimeType = mimeType;
            this.signature = signature;
        }

        boolean matchesSignature(byte[] content) {
            if (content.length < signature.length) {
                return false;
            }
            for (int i = 0; i < signature.length; i++) {
                if (content[i] != signature[i]) {
                    return false;
                }
            }
            return true;
        }
    }
}
