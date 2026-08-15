package com.gighub.document.service;

import com.gighub.auth.security.AuthPrincipal;
import com.gighub.common.exception.ConflictException;
import com.gighub.common.exception.RoleMismatchException;
import com.gighub.common.exception.ValidationException;
import com.gighub.document.exception.DocumentNotFoundException;
import com.gighub.document.mapper.DocumentQueryMapper;
import com.gighub.document.mapper.result.ShareCandidateRow;
import com.gighub.member.domain.UserRole;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

/**
 * {@code POST /api/documents/{documentId}/shares}의 공유 생성 요청을 DEC-DOCUMENT-SHARE-UNIT
 * 결정값으로 검증하고, 서버가 결정한 Work Case·OWNER를 함께 돌려줍니다.
 *
 * <p>이 Component는 아무것도 쓰지 않습니다. 검증과 파생만 담당하고 공유 행 생성은 후속
 * Transaction 경계가 맡습니다. 활성 공유 중복(409)도 여기서 미리 확인하지 않습니다. 조회와
 * 삽입 사이에 다른 요청이 끼어들 수 있어 선확인은 경쟁 조건을 막지 못하고, 최종 판정은
 * {@code uk_document_shares_active} 제약이 합니다.</p>
 *
 * <p>거절 응답은 승인 오류 목록을 그대로 따릅니다.</p>
 * <ul>
 *   <li>WORKER가 아니면 {@code 403 ROLE_MISMATCH}</li>
 *   <li>문서 없음·삭제·비소유·잘못된 유형은 모두 {@code 404}로 존재를 숨김</li>
 *   <li>사업장 없음·비활성·후보 없음·만료 보건증은 {@code 400}의
 *       {@code fieldErrors.field=workplaceId}</li>
 *   <li>후보 Work Case 복수는 {@code 409}이며 임의 선택하지 않음</li>
 * </ul>
 */
@Component
public class HealthCertificateShareValidator {

    private static final ZoneId DATABASE_ZONE = ZoneId.of("Asia/Seoul");
    private static final String WORKPLACE_ID_FIELD = "workplaceId";

    private final DocumentQueryMapper documentQueryMapper;
    private final Clock clock;

    @Autowired
    public HealthCertificateShareValidator(DocumentQueryMapper documentQueryMapper) {
        this(documentQueryMapper, Clock.system(DATABASE_ZONE));
    }

    HealthCertificateShareValidator(DocumentQueryMapper documentQueryMapper, Clock clock) {
        this.documentQueryMapper = documentQueryMapper;
        this.clock = clock;
    }

    public ValidatedHealthCertificateShare validate(
            AuthPrincipal principal, long documentId, Long workplaceId) {
        requireWorkerRole(principal);
        requireWorkplaceId(workplaceId);
        requireUnexpiredOwnHealthCertificate(documentId, principal.getUserId());

        ShareCandidateRow candidate =
                resolveCandidate(principal.getUserId(), workplaceId);
        return new ValidatedHealthCertificateShare(
                documentId, candidate.getWorkCaseId(), candidate.getOwnerUserId());
    }

    private void requireWorkerRole(AuthPrincipal principal) {
        if (principal.getRole() != UserRole.WORKER) {
            throw new RoleMismatchException("보건증 공유는 WORKER만 사용할 수 있습니다.");
        }
    }

    private void requireWorkplaceId(Long workplaceId) {
        if (workplaceId == null) {
            throw new ValidationException(
                    "공유할 사업장을 선택해 주세요.", WORKPLACE_ID_FIELD, "REQUIRED");
        }
    }

    /**
     * 소유 여부와 만료를 서로 다른 응답으로 가릅니다.
     *
     * <p>남의 문서와 없는 문서를 같은 404로 숨겨야 문서 식별자를 훑어 존재를 알아내는 경로가
     * 막힙니다. 반대로 만료는 본인 문서에서만 나오는 상태라 숨길 것이 없고, 사용자가 무엇을
     * 해야 하는지 알려면 400으로 구분해야 합니다.</p>
     */
    private void requireUnexpiredOwnHealthCertificate(long documentId, long ownerUserId) {
        LocalDate expiresOn =
                documentQueryMapper.findOwnActiveHealthCertificateExpiry(documentId, ownerUserId);
        if (expiresOn == null) {
            throw new DocumentNotFoundException("문서를 찾을 수 없습니다.");
        }
        // 만료일 당일은 아직 유효합니다. 외부 EXPIRED 판정과 같은 경계를 씁니다.
        if (expiresOn.isBefore(LocalDate.now(clock))) {
            throw new ValidationException(
                    "만료된 보건증은 공유할 수 없습니다.", WORKPLACE_ID_FIELD, "DOCUMENT_EXPIRED");
        }
    }

    private ShareCandidateRow resolveCandidate(long workerId, long workplaceId) {
        List<ShareCandidateRow> candidates =
                documentQueryMapper.findShareCandidates(workerId, workplaceId);
        if (candidates.isEmpty()) {
            throw new ValidationException(
                    "이 사업장에는 보건증을 공유할 수 있는 근무가 없습니다.",
                    WORKPLACE_ID_FIELD,
                    "NO_SHARE_CANDIDATE");
        }
        if (candidates.size() > 1) {
            // 임의 선택은 사용자가 의도하지 않은 근무에 건강 정보를 붙일 수 있습니다.
            throw new ConflictException(
                    "이 사업장에 공유 가능한 근무가 여러 건이라 대상을 정할 수 없습니다.");
        }
        return candidates.get(0);
    }
}
