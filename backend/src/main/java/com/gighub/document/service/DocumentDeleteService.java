package com.gighub.document.service;

import com.gighub.auth.security.AuthPrincipal;

/**
 * {@code DELETE /api/documents/{documentId}}를 처리한다(DOC-006). 소유 보건증만 논리
 * 삭제하며, 근로계약서는 보존 정책으로 거부한다.
 */
public interface DocumentDeleteService {

    void delete(AuthPrincipal principal, long documentId);
}
