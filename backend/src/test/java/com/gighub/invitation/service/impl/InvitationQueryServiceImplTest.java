package com.gighub.invitation.service.impl;

import com.gighub.invitation.domain.InvitationStatus;
import com.gighub.work.domain.WorkCaseStatus;
import com.gighub.auth.security.AuthPrincipal;
import com.gighub.common.exception.ApiException;
import com.gighub.common.exception.ConflictException;
import com.gighub.common.exception.RoleMismatchException;
import com.gighub.invitation.config.InvitationProperties;
import com.gighub.invitation.dto.InvitationDetailResponse;
import com.gighub.invitation.exception.InvitationAlreadyAcceptedException;
import com.gighub.invitation.exception.InvitationExpiredException;
import com.gighub.invitation.exception.InvitationNotFoundException;
import com.gighub.invitation.exception.InvitationRevokedException;
import com.gighub.invitation.exception.InvitationTermsChangedException;
import com.gighub.invitation.mapper.InvitationMapperTestDouble;
import com.gighub.invitation.mapper.result.InvitationRow;
import com.gighub.invitation.mapper.result.InvitationWorkCaseRow;

import com.gighub.invitation.token.InvitationTokenCodec;
import com.gighub.member.domain.UserRole;
import org.junit.jupiter.api.Test;


import java.security.MessageDigest;
import java.time.Clock;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 초대 조회의 상태 판정과 검증 순서를 확인합니다.
 */
class InvitationQueryServiceImplTest {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final long INVITATION_ID = 41L;
    private static final long WORK_CASE_ID = 7L;
    private static final LocalDateTime STARTS_AT = LocalDateTime.of(2026, 8, 20, 10, 0);

    private final InvitationTokenCodec codec = new InvitationTokenCodec(
            InvitationProperties.of(
                    "service-test-invitation-secret-0123456789", null, "http://localhost:5173")
    );
    private final String token = codec.deriveToken(INVITATION_ID);
    private final StubInvitationMapper mapper = new StubInvitationMapper();

    @Test
    void returnsApprovedTermsForAuthenticatedWorker() {
        mapper.invitation = pendingInvitation();
        mapper.workCase = draftWorkCase(1);

        InvitationDetailResponse response = service(STARTS_AT.minusDays(1L))
                .findByToken(worker(), token);

        assertEquals("주말 홀 서빙", response.getTitle());
        assertEquals("강남점", response.getWorkplaceName());
        assertEquals(STARTS_AT.atZone(SEOUL).toInstant(), response.getStartsAt());
        assertEquals(STARTS_AT.plusHours(8L).atZone(SEOUL).toInstant(), response.getEndsAt());
        assertEquals(60, response.getBreakMinutes());
        assertFalse(response.isBreakPaid());
        assertEquals(120_000L, response.getDailyWage());
        assertEquals(1, response.getTermsVersion());
        assertEquals(STARTS_AT.atZone(SEOUL).toInstant(), response.getExpiresAt());
        // 배지 등급 산정 전까지는 활성 Badge 없음과 같은 null입니다.
        assertNull(response.getOwnerBadge());
    }

    @Test
    void nonWorkerIsRejectedBeforeTheTokenIsEvenLookedUp() {
        mapper.invitation = pendingInvitation();
        mapper.workCase = draftWorkCase(1);

        assertThrows(
                RoleMismatchException.class,
                () -> service(STARTS_AT.minusDays(1L)).findByToken(owner(), token)
        );
        assertTrue(mapper.lookedUpHashes.isEmpty(), "역할 거절이 저장소 조회보다 앞서야 합니다.");
    }

    @Test
    void malformedTokenIsNotFoundWithoutTouchingStorage() {
        ApiException exception = assertThrows(
                InvitationNotFoundException.class,
                () -> service(STARTS_AT.minusDays(1L)).findByToken(worker(), "not-a-token")
        );

        assertEquals("초대 링크를 찾을 수 없습니다.", exception.getMessage());
        assertTrue(mapper.lookedUpHashes.isEmpty());
    }

    @Test
    void unknownTokenLooksExactlyLikeAMalformedOne() {
        mapper.invitation = null;

        ApiException unknown = assertThrows(
                InvitationNotFoundException.class,
                () -> service(STARTS_AT.minusDays(1L)).findByToken(worker(), token)
        );
        ApiException malformed = assertThrows(
                InvitationNotFoundException.class,
                () -> service(STARTS_AT.minusDays(1L)).findByToken(worker(), "not-a-token")
        );

        assertEquals(malformed.getStatus(), unknown.getStatus());
        assertEquals(malformed.getCode(), unknown.getCode());
        assertEquals(malformed.getMessage(), unknown.getMessage());
        // 조회는 Hash로만 수행해야 합니다.
        assertEquals(1, mapper.lookedUpHashes.size());
        assertTrue(MessageDigest.isEqual(codec.hash(token), mapper.lookedUpHashes.get(0)));
    }

    @Test
    void terminalStatesMapToTheirApprovedErrors() {
        mapper.workCase = draftWorkCase(1);

        mapper.invitation = invitation("ACCEPTED", 1, STARTS_AT);
        assertThrows(
                InvitationAlreadyAcceptedException.class,
                () -> service(STARTS_AT.minusDays(1L)).findByToken(worker(), token)
        );

        mapper.invitation = invitation("REVOKED", 1, STARTS_AT);
        assertThrows(
                InvitationRevokedException.class,
                () -> service(STARTS_AT.minusDays(1L)).findByToken(worker(), token)
        );

        mapper.invitation = invitation("EXPIRED", 1, STARTS_AT);
        assertThrows(
                InvitationExpiredException.class,
                () -> service(STARTS_AT.minusDays(1L)).findByToken(worker(), token)
        );

        // 계약에 없는 상태는 조용히 통과하지 않아야 합니다.
        mapper.invitation = invitation("REJECTED", 1, STARTS_AT);
        assertThrows(
                ConflictException.class,
                () -> service(STARTS_AT.minusDays(1L)).findByToken(worker(), token)
        );

        assertEquals(0, mapper.expiredIds.size(), "종료된 초대를 만료로 덮으면 안 됩니다.");
    }

    @Test
    void expiryBoundaryIsInclusiveAndPersistsTheTransition() {
        mapper.invitation = pendingInvitation();
        mapper.workCase = draftWorkCase(1);

        // 만료 1초 전에는 아직 조회됩니다.
        assertEquals(
                "주말 홀 서빙",
                service(STARTS_AT.minusSeconds(1L)).findByToken(worker(), token).getTitle()
        );
        assertTrue(mapper.expiredIds.isEmpty());

        // 만료 시각과 같은 순간부터 사용할 수 없습니다.
        mapper.invitation = pendingInvitation();
        assertThrows(
                InvitationExpiredException.class,
                () -> service(STARTS_AT).findByToken(worker(), token)
        );
        assertEquals(List.of(INVITATION_ID), mapper.expiredIds);
    }

    @Test
    void expiryStopsWhenTheLockedPendingInvitationWasNotUpdated() {
        mapper.invitation = pendingInvitation();
        mapper.workCase = draftWorkCase(1);
        mapper.markExpiredResult = 0;

        assertThrows(
                IllegalStateException.class,
                () -> service(STARTS_AT).findByToken(worker(), token));
    }

    @Test
    void changedTermsBlockConfirmationWithoutShowingTheOldSnapshot() {
        mapper.invitation = invitation("PENDING", 1, STARTS_AT);
        mapper.workCase = draftWorkCase(2);

        assertThrows(
                InvitationTermsChangedException.class,
                () -> service(STARTS_AT.minusDays(1L)).findByToken(worker(), token)
        );
    }

    @Test
    void matchedOrClosedWorkCasesDoNotExposeTerms() {
        mapper.invitation = pendingInvitation();
        mapper.workCase = InvitationWorkCaseRow.builder()
                .workCaseId(WORK_CASE_ID)
                .employerId(3L)
                .workerId(9L)
                .title("주말 홀 서빙")
                .workplaceName("강남점")
                .startsAt(STARTS_AT)
                .endsAt(STARTS_AT.plusHours(8L))
                .breakMinutes(60)
                .breakPaid(false)
                .dailyWage(120_000L)
                .termsVersion(1)
                .status(WorkCaseStatus.ACCEPTED)
                .build();
        assertThrows(
                InvitationAlreadyAcceptedException.class,
                () -> service(STARTS_AT.minusDays(1L)).findByToken(worker(), token)
        );

        mapper.invitation = pendingInvitation();
        mapper.workCase = InvitationWorkCaseRow.builder()
                .workCaseId(WORK_CASE_ID)
                .employerId(3L)
                .workerId(null)
                .title("주말 홀 서빙")
                .workplaceName("강남점")
                .startsAt(STARTS_AT)
                .endsAt(STARTS_AT.plusHours(8L))
                .breakMinutes(60)
                .breakPaid(false)
                .dailyWage(120_000L)
                .termsVersion(1)
                .status(WorkCaseStatus.CANCELED)
                .build();
        assertThrows(
                ConflictException.class,
                () -> service(STARTS_AT.minusDays(1L)).findByToken(worker(), token)
        );
    }

    @Test
    void noFailureMessageEchoesTheToken() {
        mapper.invitation = null;
        List<ApiException> failures = new ArrayList<>();

        failures.add(assertThrows(
                InvitationNotFoundException.class,
                () -> service(STARTS_AT.minusDays(1L)).findByToken(worker(), token)));
        failures.add(assertThrows(
                RoleMismatchException.class,
                () -> service(STARTS_AT.minusDays(1L)).findByToken(owner(), token)));

        mapper.invitation = invitation("REVOKED", 1, STARTS_AT);
        failures.add(assertThrows(
                InvitationRevokedException.class,
                () -> service(STARTS_AT.minusDays(1L)).findByToken(worker(), token)));

        for (ApiException failure : failures) {
            assertFalse(failure.getMessage().contains(token));
        }
    }

    private InvitationQueryServiceImpl service(LocalDateTime now) {
        return new InvitationQueryServiceImpl(mapper, codec, fixedClock(now));
    }

    private static Clock fixedClock(LocalDateTime now) {
        return Clock.fixed(now.atZone(SEOUL).toInstant(), SEOUL);
    }

    private InvitationRow pendingInvitation() {
        return invitation("PENDING", 1, STARTS_AT);
    }

    private InvitationRow invitation(String status, int expectedTermsVersion, LocalDateTime expiresAt) {
        return InvitationRow.builder()
                .id(INVITATION_ID)
                .workCaseId(WORK_CASE_ID)
                .tokenHash(codec.hash(token))
                .status(InvitationStatus.valueOf(status))
                .expectedTermsVersion(expectedTermsVersion)
                .expiresAt(expiresAt)
                .build();
    }

    private InvitationWorkCaseRow draftWorkCase(int termsVersion) {
        return InvitationWorkCaseRow.builder()
                .workCaseId(WORK_CASE_ID)
                .employerId(3L)
                .workerId(null)
                .title("주말 홀 서빙")
                .workplaceName("강남점")
                .startsAt(STARTS_AT)
                .endsAt(STARTS_AT.plusHours(8L))
                .breakMinutes(60)
                .breakPaid(false)
                .dailyWage(120_000L)
                .termsVersion(termsVersion)
                .status(WorkCaseStatus.DRAFT)
                .build();
    }

    private static AuthPrincipal worker() {
        return new AuthPrincipal(11L, UserRole.WORKER, "김알바");
    }

    private static AuthPrincipal owner() {
        return new AuthPrincipal(3L, UserRole.OWNER, "김사장");
    }

    /** 조회 호출 자체를 관찰해야 해서 Mock 대신 직접 만든 Stub을 씁니다. */
    private static final class StubInvitationMapper extends InvitationMapperTestDouble {

        private final List<byte[]> lookedUpHashes = new ArrayList<>();
        private final List<Long> expiredIds = new ArrayList<>();

        private InvitationRow invitation;
        private InvitationWorkCaseRow workCase;
        private int markExpiredResult = 1;

        @Override
        public InvitationRow findByTokenHashForUpdate(byte[] tokenHash) {
            lookedUpHashes.add(tokenHash);
            return invitation;
        }

        @Override
        public InvitationWorkCaseRow findWorkCaseForInvitation(long workCaseId) {
            return workCase;
        }

        @Override
        public int markExpired(long invitationId) {
            if (markExpiredResult == 1) {
                expiredIds.add(invitationId);
            }
            return markExpiredResult;
        }
    }
}
