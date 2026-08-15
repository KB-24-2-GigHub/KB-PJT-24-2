package com.gighub.document.service;

import com.gighub.auth.security.AuthPrincipal;
import com.gighub.common.exception.ConflictException;
import com.gighub.common.exception.RoleMismatchException;
import com.gighub.common.exception.ValidationException;
import com.gighub.document.exception.DocumentNotFoundException;
import com.gighub.document.mapper.DocumentQueryMapper;
import com.gighub.document.mapper.result.ShareCandidateRow;
import com.gighub.member.domain.UserRole;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 보건증 공유 생성 요청의 권한·후보 결정 경계를 검증합니다.
 *
 * <p>이 Validator의 핵심은 Client가 보낸 값으로 공유 대상을 정하지 않는 것입니다. 요청은
 * {@code workplaceId}만 주고 Work Case와 OWNER는 서버 조회 결과에서만 나옵니다.</p>
 */
class HealthCertificateShareValidatorTest {

    private static final long WORKER_ID = 7L;
    private static final long DOCUMENT_ID = 11L;
    private static final long WORKPLACE_ID = 3L;
    private static final LocalDate TODAY = LocalDate.of(2026, 8, 15);

    private final DocumentQueryMapper documentQueryMapper = mock(DocumentQueryMapper.class);

    private final HealthCertificateShareValidator validator = new HealthCertificateShareValidator(
            documentQueryMapper,
            Clock.fixed(TODAY.atStartOfDay(ZoneId.of("Asia/Seoul")).toInstant(),
                    ZoneId.of("Asia/Seoul")));

    @Test
    void rejectsAnOwnerWithRoleMismatchBeforeTouchingTheDatabase() {
        assertThrows(RoleMismatchException.class,
                () -> validator.validate(owner(), DOCUMENT_ID, WORKPLACE_ID));

        verifyNoInteractions(documentQueryMapper);
    }

    @Test
    void rejectsAMissingWorkplaceIdAsAFieldError() {
        ValidationException thrown = assertThrows(ValidationException.class,
                () -> validator.validate(worker(), DOCUMENT_ID, null));

        assertEquals("workplaceId", thrown.getFieldErrors().get(0).getField());
        verifyNoInteractions(documentQueryMapper);
    }

    /** 없는 문서·남의 문서·삭제된 문서·근로계약서는 모두 같은 404다. */
    @Test
    void hidesAnInvisibleDocumentBehindNotFound() {
        when(documentQueryMapper.findOwnActiveHealthCertificateExpiry(DOCUMENT_ID, WORKER_ID))
                .thenReturn(null);

        assertThrows(DocumentNotFoundException.class,
                () -> validator.validate(worker(), DOCUMENT_ID, WORKPLACE_ID));

        // 문서가 보이지 않으면 사업장 후보를 조회할 이유가 없다.
        verify(documentQueryMapper, never()).findShareCandidates(anyLong(), anyLong());
    }

    @Test
    void rejectsAnExpiredCertificateAsAWorkplaceFieldErrorRatherThanNotFound() {
        when(documentQueryMapper.findOwnActiveHealthCertificateExpiry(DOCUMENT_ID, WORKER_ID))
                .thenReturn(TODAY.minusDays(1));

        ValidationException thrown = assertThrows(ValidationException.class,
                () -> validator.validate(worker(), DOCUMENT_ID, WORKPLACE_ID));

        assertEquals("workplaceId", thrown.getFieldErrors().get(0).getField());
        assertEquals("DOCUMENT_EXPIRED", thrown.getFieldErrors().get(0).getReason());
    }

    /** 만료일 당일은 아직 유효하다. 외부 EXPIRED 판정과 같은 경계다. */
    @Test
    void acceptsACertificateOnItsExpiryDate() {
        when(documentQueryMapper.findOwnActiveHealthCertificateExpiry(DOCUMENT_ID, WORKER_ID))
                .thenReturn(TODAY);
        when(documentQueryMapper.findShareCandidates(WORKER_ID, WORKPLACE_ID))
                .thenReturn(List.of(candidate(21L, 5L)));

        ValidatedHealthCertificateShare validated =
                validator.validate(worker(), DOCUMENT_ID, WORKPLACE_ID);

        assertEquals(21L, validated.workCaseId());
    }

    /** 사업장 없음·비활성·후보 없음은 구분하지 않고 같은 400이다. */
    @Test
    void rejectsAWorkplaceWithNoCandidateAsAFieldError() {
        when(documentQueryMapper.findOwnActiveHealthCertificateExpiry(DOCUMENT_ID, WORKER_ID))
                .thenReturn(TODAY.plusMonths(6));
        when(documentQueryMapper.findShareCandidates(WORKER_ID, WORKPLACE_ID))
                .thenReturn(List.of());

        ValidationException thrown = assertThrows(ValidationException.class,
                () -> validator.validate(worker(), DOCUMENT_ID, WORKPLACE_ID));

        assertEquals("workplaceId", thrown.getFieldErrors().get(0).getField());
        assertEquals("NO_SHARE_CANDIDATE", thrown.getFieldErrors().get(0).getReason());
    }

    @Test
    void rejectsMultipleCandidatesWithConflictInsteadOfPickingOne() {
        when(documentQueryMapper.findOwnActiveHealthCertificateExpiry(DOCUMENT_ID, WORKER_ID))
                .thenReturn(TODAY.plusMonths(6));
        when(documentQueryMapper.findShareCandidates(WORKER_ID, WORKPLACE_ID))
                .thenReturn(List.of(candidate(21L, 5L), candidate(22L, 5L)));

        assertThrows(ConflictException.class,
                () -> validator.validate(worker(), DOCUMENT_ID, WORKPLACE_ID));
    }

    @Test
    void derivesTheWorkCaseAndOwnerFromTheServerLookupOnly() {
        when(documentQueryMapper.findOwnActiveHealthCertificateExpiry(DOCUMENT_ID, WORKER_ID))
                .thenReturn(TODAY.plusMonths(6));
        when(documentQueryMapper.findShareCandidates(WORKER_ID, WORKPLACE_ID))
                .thenReturn(List.of(candidate(21L, 5L)));

        ValidatedHealthCertificateShare validated =
                validator.validate(worker(), DOCUMENT_ID, WORKPLACE_ID);

        assertEquals(DOCUMENT_ID, validated.documentId());
        assertEquals(21L, validated.workCaseId());
        assertEquals(5L, validated.sharedWithUserId());
        // 조회는 Session principal의 사용자 ID로만 이루어진다.
        verify(documentQueryMapper).findShareCandidates(WORKER_ID, WORKPLACE_ID);
        verify(documentQueryMapper).findOwnActiveHealthCertificateExpiry(DOCUMENT_ID, WORKER_ID);
    }

    private ShareCandidateRow candidate(long workCaseId, long ownerUserId) {
        return ShareCandidateRow.builder()
                .workCaseId(workCaseId)
                .ownerUserId(ownerUserId)
                .build();
    }

    private AuthPrincipal worker() {
        return new AuthPrincipal(WORKER_ID, UserRole.WORKER, "이알바");
    }

    private AuthPrincipal owner() {
        return new AuthPrincipal(5L, UserRole.OWNER, "김대표");
    }
}
