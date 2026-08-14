package com.gighub.document.validation;

/**
 * Servlet {@code MultipartFile}에서 검증에 필요한 값만 뽑아낸 경계값입니다. 검증·등록
 * Application 계층은 이 값만 알고 Servlet/Spring MVC 타입에 의존하지 않는다(RF-02).
 */
public record UploadedFile(byte[] content, String originalFilename, String declaredContentType) {
}
