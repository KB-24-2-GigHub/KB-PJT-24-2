package com.gighub.document.service;

import com.gighub.document.validation.ValidatedHealthCertificateFile;

import java.time.LocalDate;

/**
 * {@link HealthCertificateRegistrationValidator}가 역할·문서 유형·발급일·파일 검증을 모두
 * 마친 보건증 등록 요청입니다. 이후 등록 Transaction만 이 값을 소비합니다.
 */
public record ValidatedHealthCertificateRegistration(
        long ownerUserId,
        LocalDate issuedDate,
        ValidatedHealthCertificateFile file) {
}
