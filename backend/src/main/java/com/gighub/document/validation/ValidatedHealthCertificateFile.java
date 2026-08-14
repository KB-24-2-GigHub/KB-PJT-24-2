package com.gighub.document.validation;

/**
 * {@link HealthCertificateFileValidator}가 확장자·MIME·Signature·Checksum 검증을 마친
 * 보건증 파일입니다. {@code storageExtension}·{@code mimeType}은 검증된 실제 내용을
 * 기준으로 서버가 정한 값이며 클라이언트가 보낸 원본 값을 그대로 담지 않습니다.
 */
public record ValidatedHealthCertificateFile(
        byte[] content,
        String storageExtension,
        String mimeType,
        byte[] checksum) {
}
