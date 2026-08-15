package com.gighub.document.service;

import com.gighub.auth.security.AuthPrincipal;

/**
 * {@code DELETE /api/documents/{documentId}/shares/{workplaceId}}를 처리한다(DOC-008). 그
 * 사업장과 연결된 현재 문서의 ACTIVE 공유만 철회하며, 대상이 없어도 소유자 요청이면
 * 성공이다(멱등).
 */
public interface HealthCertificateShareRevokeService {

    void revoke(AuthPrincipal principal, long documentId, long workplaceId);
}
