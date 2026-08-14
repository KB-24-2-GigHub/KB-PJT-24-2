package com.gighub.document.storage;

/** 보건증 파일 Storage Key 규칙입니다(DEC-DOCUMENT-STORAGE, DOC-010). */
public final class HealthCertificateStorageKeys {

    private HealthCertificateStorageKeys() {
    }

    /** DB Commit 전 임시로 쓰는 Object의 Key입니다. */
    public static String pendingKey(long ownerUserId, long documentId, String extension) {
        return "health-certificates/%d/%d/.pending/v1.%s"
                .formatted(ownerUserId, documentId, extension);
    }

    /** 승격 완료된 최종 Object의 Key입니다. */
    public static String finalKey(long ownerUserId, long documentId, String extension) {
        return "health-certificates/%d/%d/v1.%s".formatted(ownerUserId, documentId, extension);
    }
}
