package com.gighub.document.service;

import java.util.Objects;

/** DB 잠금 밖에서 수행한 문서 파일 검증 결과입니다. */
final class DocumentFileReadResult {

    enum Status {
        VERIFIED,
        FILE_UNAVAILABLE,
        CHECKSUM_MISMATCH
    }

    private final Status status;
    private final byte[] content;

    private DocumentFileReadResult(Status status, byte[] content) {
        this.status = Objects.requireNonNull(status, "status");
        this.content = content;
    }

    static DocumentFileReadResult verified(byte[] content) {
        return new DocumentFileReadResult(
                Status.VERIFIED,
                Objects.requireNonNull(content, "content"));
    }

    static DocumentFileReadResult fileUnavailable() {
        return new DocumentFileReadResult(Status.FILE_UNAVAILABLE, null);
    }

    static DocumentFileReadResult checksumMismatch() {
        return new DocumentFileReadResult(Status.CHECKSUM_MISMATCH, null);
    }

    boolean isVerified() {
        return status == Status.VERIFIED;
    }

    Status getStatus() {
        return status;
    }

    byte[] getContent() {
        if (!isVerified()) {
            throw new IllegalStateException("검증되지 않은 문서에는 반환할 파일 내용이 없습니다.");
        }
        return content;
    }
}
