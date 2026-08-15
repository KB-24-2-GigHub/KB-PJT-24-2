package com.gighub.document.service;

/**
 * 검증을 통과한 보건증 공유 한 건입니다.
 *
 * <p>{@code workCaseId}와 {@code sharedWithUserId}는 요청이 아니라 서버가 사업장·근무 관계
 * 조회로 파생한 값입니다(DEC-DOCUMENT-SHARE-UNIT). Client는 {@code workplaceId}만 보냅니다.</p>
 *
 * @param documentId       공유할 소유 보건증
 * @param workCaseId       서버가 결정한 유일한 후보 Work Case
 * @param sharedWithUserId 그 사업장의 OWNER
 */
public record ValidatedHealthCertificateShare(
        long documentId, long workCaseId, long sharedWithUserId) {
}
