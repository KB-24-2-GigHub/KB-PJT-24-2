package com.gighub.document.service;

import com.gighub.auth.security.AuthPrincipal;
import com.gighub.common.exception.RoleMismatchException;
import com.gighub.document.exception.DocumentNotFoundException;
import com.gighub.document.mapper.ContractDocumentWriteMapper;
import com.gighub.document.mapper.result.DocumentOwnershipRow;
import com.gighub.member.domain.UserRole;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * {@code DELETE /api/documents/{documentId}/shares/{workplaceId}}의 보건증 공유 철회를
 * 처리한다(DOC-008).
 *
 * <p>대상 행이 없어도 오류가 아니다. 소유 여부만 확인하고 실제 철회된 행 수는 응답에
 * 반영하지 않는다. 이미 삭제된 문서(모든 ACTIVE 공유가 이미 철회된 상태)에 대한 반복 호출도
 * 자연히 대상 없는 성공이 된다.</p>
 */
@Service
public class HealthCertificateShareRevokeServiceImpl implements HealthCertificateShareRevokeService {

    private static final ZoneId DATABASE_ZONE = ZoneId.of("Asia/Seoul");
    private static final String HEALTH_CERTIFICATE_TYPE = "HEALTH_CERTIFICATE";

    private final ContractDocumentWriteMapper documentMapper;
    private final Clock clock;

    @Autowired
    public HealthCertificateShareRevokeServiceImpl(ContractDocumentWriteMapper documentMapper) {
        this(documentMapper, Clock.system(DATABASE_ZONE));
    }

    HealthCertificateShareRevokeServiceImpl(
            ContractDocumentWriteMapper documentMapper, Clock clock) {
        this.documentMapper = documentMapper;
        this.clock = clock;
    }

    @Override
    @Transactional
    public void revoke(AuthPrincipal principal, long documentId, long workplaceId) {
        requireWorkerRole(principal);

        DocumentOwnershipRow row = documentMapper.lockOwnDocument(documentId, principal.getUserId());
        if (row == null || !HEALTH_CERTIFICATE_TYPE.equals(row.getDocumentType())) {
            // 없는 문서, 비소유, 근로계약서는 모두 같은 404로 존재를 숨긴다.
            throw new DocumentNotFoundException("문서를 찾을 수 없습니다.");
        }

        documentMapper.revokeActiveSharesByWorkplace(
                documentId, workplaceId, LocalDateTime.now(clock));
    }

    private void requireWorkerRole(AuthPrincipal principal) {
        if (principal.getRole() != UserRole.WORKER) {
            throw new RoleMismatchException("보건증 공유 철회는 WORKER만 사용할 수 있습니다.");
        }
    }
}
