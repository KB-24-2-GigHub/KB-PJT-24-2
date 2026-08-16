package com.gighub.document.service;

import com.gighub.auth.security.AuthPrincipal;

/** 소유 WORKER가 보건증을 한 사업장에 공유하는 경계입니다(DOC-007). */
public interface HealthCertificateShareService {

    /**
     * 공유 한 건을 만들고 생성된 공유 식별자를 돌려줍니다.
     *
     * @param workplaceId Client가 보낸 유일한 입력. Work Case와 OWNER는 서버가 파생합니다
     */
    long share(AuthPrincipal principal, long documentId, Long workplaceId);
}
