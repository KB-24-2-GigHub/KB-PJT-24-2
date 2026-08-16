package com.gighub.document.storage;

import java.util.Set;

/**
 * 계약 문서 파일의 비공개 저장 경계입니다(DEC-DOCUMENT-STORAGE).
 *
 * <p>쓰기는 항상 임시 Key에 먼저 기록하고, DB Transaction이 Commit된 뒤에만 최종 Key로
 * 승격한다. 승격은 Checksum을 검증하며, 이미 같은 Checksum의 최종 Object가 있으면 재승격
 * 없이 성공으로 간주해 재시도를 안전하게 만든다.</p>
 */
public interface DocumentStorageAdapter {

    /** DB Transaction Commit 전 임시 Key에 내용을 쓴다. */
    void writePending(String pendingKey, byte[] content);

    /**
     * 임시 Key의 내용을 검증한 뒤 최종 Key로 승격한다.
     *
     * @param expectedSha256 DB에 기록하는 Checksum과 같은 값이어야 한다
     */
    void promote(String pendingKey, String finalKey, byte[] expectedSha256);

    /** 비공개 Key의 내용을 읽는다. 없으면 {@link DocumentStorageIntegrityException}을 던진다. */
    byte[] read(String key);

    /** 비공개 Key가 이미 존재하는지 확인한다. */
    boolean exists(String key);

    /** 실패 보상 등에서 임시 Key를 정리한다. 이미 없으면 아무 일도 하지 않는다. */
    void deletePending(String pendingKey);

    /**
     * 보존 만료 파기(DOC-012)에서 승격된 최종 Key를 지운다. 이미 없으면 아무 일도 하지
     * 않는다 — 재실행이 안전해야 한다.
     */
    void deleteFinal(String finalKey);

    /**
     * Rollback된 근무의 문서 하위에 남은 임시 Object를 최선 노력으로 정리한다.
     *
     * @param retainedDocumentIds 이미 Commit된 문서라 임시 Fallback을 보존해야 하는 식별자
     */
    void deletePendingByWorkCaseId(long workCaseId, Set<Long> retainedDocumentIds);
}
