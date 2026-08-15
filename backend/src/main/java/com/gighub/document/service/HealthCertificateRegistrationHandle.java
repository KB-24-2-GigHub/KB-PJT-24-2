package com.gighub.document.service;

import java.time.LocalDate;

/**
 * {@link HealthCertificateRegisterTransaction}이 Commit한 {@code documents} 행의 승격에
 * 필요한 최소 정보입니다. 파일 승격은 이 Transaction Commit 뒤 별도 Best-Effort 경계에서
 * 일어난다(DEC-DOCUMENT-STORAGE).
 */
public record HealthCertificateRegistrationHandle(
        long documentId,
        String pendingStorageKey,
        String finalStorageKey,
        byte[] checksum,
        LocalDate expiresDate) {
}
