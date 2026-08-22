package com.gighub.invitation;

import com.gighub.auth.security.AuthPrincipal;
import com.gighub.config.RootConfig;
import com.gighub.invitation.dto.InvitationDetailResponse;
import com.gighub.invitation.exception.InvitationExpiredException;
import com.gighub.invitation.exception.InvitationNotFoundException;
import com.gighub.invitation.exception.InvitationRevokedException;
import com.gighub.invitation.exception.InvitationTermsChangedException;
import com.gighub.invitation.service.InvitationIssueService;
import com.gighub.invitation.service.InvitationQueryService;
import com.gighub.member.domain.UserRole;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 발급한 Link가 실제로 조회되는지, 그리고 생명주기 전이가 두 흐름에서 같게 보이는지
 * 확인합니다.
 *
 * <p>발급은 Token 원문을 만들고 조회는 Hash로 찾습니다. 두 흐름을 따로 검증하면 파생과 Hash
 * 계산이 어긋나도 각각의 테스트는 통과할 수 있어, 한 번은 실제 DB를 사이에 두고 이어서
 * 확인합니다.</p>
 */
@Tag("database")
class InvitationLifecycleDatabaseIntegrationTest {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    @Test
    @Timeout(90)
    void issuedLinkIsReadableAndFollowsTheSameLifecycleAsStorage() {
        try (AnnotationConfigApplicationContext context =
                     new AnnotationConfigApplicationContext(RootConfig.class)) {
            JdbcTemplate jdbcTemplate = new JdbcTemplate(context.getBean(DataSource.class));
            InvitationIssueService issueService = context.getBean(InvitationIssueService.class);
            InvitationQueryService queryService = context.getBean(InvitationQueryService.class);

            String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
            Fixture fixture = insertWorkCaseFixture(jdbcTemplate, suffix);

            try {
                String token = issueToken(issueService, fixture);
                verifyIssuedLinkReturnsTheStoredTerms(queryService, fixture, token);
                verifyReissueMakesThePreviousLinkRevoked(
                        issueService, queryService, fixture, token);
                verifyTermsChangeBlocksTheOutstandingLink(
                        jdbcTemplate, issueService, queryService, fixture);
                verifyExpiredLinkIsRejectedAndFreesTheActiveSlot(
                        jdbcTemplate, issueService, queryService, fixture);
            } finally {
                cleanUp(jdbcTemplate, fixture);
            }
        }
    }

    /** 발급한 Link의 Token으로 저장된 조건이 그대로 보여야 합니다. */
    private void verifyIssuedLinkReturnsTheStoredTerms(
            InvitationQueryService queryService,
            Fixture fixture,
            String token) {
        InvitationDetailResponse detail = queryService.findByToken(fixture.worker(), token);

        assertEquals("주말 홀 서빙", detail.getTitle());
        assertEquals("강남점", detail.getWorkplaceName());
        assertEquals(fixture.startsAt.atZone(SEOUL).toInstant(), detail.getStartsAt());
        assertEquals(fixture.startsAt.plusHours(8L).atZone(SEOUL).toInstant(), detail.getEndsAt());
        assertEquals(60, detail.getBreakMinutes());
        assertFalse(detail.isBreakPaid());
        assertEquals(120_000L, detail.getDailyWage());
        assertEquals(1, detail.getTermsVersion());
        assertEquals(fixture.startsAt.atZone(SEOUL).toInstant(), detail.getExpiresAt());
        assertNotNull(detail.getOwnerBadge());
        assertEquals("TRUST_OWNER", detail.getOwnerBadge().getBadgeType());
        assertEquals(0, detail.getOwnerBadge().getLevel());
    }

    /** 재발급 직후 이전 Link는 미존재가 아니라 철회로 보여야 합니다. */
    private void verifyReissueMakesThePreviousLinkRevoked(
            InvitationIssueService issueService,
            InvitationQueryService queryService,
            Fixture fixture,
            String previousToken) {
        String reissuedToken = tokenOf(
                issueService.reissue(fixture.owner(), fixture.workCaseId).getInviteUrl());

        assertThrows(
                InvitationRevokedException.class,
                () -> queryService.findByToken(fixture.worker(), previousToken)
        );
        // 새 Link는 정상 동작해야 합니다.
        assertEquals(
                "주말 홀 서빙",
                queryService.findByToken(fixture.worker(), reissuedToken).getTitle()
        );
    }

    /**
     * 조건 Version이 바뀌면 아직 살아 있는 Link도 확정에 쓸 수 없어야 합니다.
     *
     * <p>실제 수정 흐름(#154)은 활성 초대를 함께 철회하므로 보통 철회로 끝납니다. 여기서는
     * 철회 없이 Version만 올려, Version 검증 자체가 이전 Snapshot을 막는지 확인합니다.</p>
     */
    private void verifyTermsChangeBlocksTheOutstandingLink(
            JdbcTemplate jdbcTemplate,
            InvitationIssueService issueService,
            InvitationQueryService queryService,
            Fixture fixture) {
        String token = issueToken(issueService, fixture);

        jdbcTemplate.update(
                "UPDATE work_cases SET terms_version = terms_version + 1, agreed_wage = 150000"
                        + " WHERE id = ?",
                fixture.workCaseId);

        assertThrows(
                InvitationTermsChangedException.class,
                () -> queryService.findByToken(fixture.worker(), token)
        );
    }

    /**
     * 만료된 Link는 410으로 끝나고, 그 전이가 활성 Slot을 비워 재발급이 가능해야 합니다.
     */
    private void verifyExpiredLinkIsRejectedAndFreesTheActiveSlot(
            JdbcTemplate jdbcTemplate,
            InvitationIssueService issueService,
            InvitationQueryService queryService,
            Fixture fixture) {
        // 활성 초대만 과거로 당겨 만료 상황을 만듭니다. 근무 자체를 과거로 옮기면 발급
        // 가능 조건까지 함께 깨져 무엇을 검증하는지 흐려집니다.
        String token = issueToken(issueService, fixture);
        jdbcTemplate.update(
                "UPDATE work_invitations SET expires_at = ? "
                        + "WHERE work_case_id = ? AND status = 'PENDING'",
                LocalDateTime.now().minusMinutes(1L),
                fixture.workCaseId);

        Long expiringId = jdbcTemplate.queryForObject(
                "SELECT id FROM work_invitations WHERE work_case_id = ? AND status = 'PENDING'",
                Long.class,
                fixture.workCaseId);

        assertThrows(
                InvitationExpiredException.class,
                () -> queryService.findByToken(fixture.worker(), token)
        );
        // 410 예외가 Rollback을 일으키면 이 전이가 사라져 활성 Slot이 계속 막힙니다.
        assertEquals(
                "EXPIRED",
                jdbcTemplate.queryForObject(
                        "SELECT status FROM work_invitations WHERE id = ?",
                        String.class,
                        expiringId)
        );

        // 만료로 활성 Slot이 비었으므로 새 발급이 가능합니다.
        String renewed = issueToken(issueService, fixture);
        assertEquals(
                "주말 홀 서빙",
                queryService.findByToken(fixture.worker(), renewed).getTitle()
        );

        // 만료된 이전 Link는 계속 만료로 남습니다.
        assertThrows(
                InvitationExpiredException.class,
                () -> queryService.findByToken(fixture.worker(), token)
        );

        // 형식만 같은 다른 Token은 미존재로 끝납니다.
        String unknown = renewed.substring(0, 42)
                + (renewed.charAt(42) == 'A' ? 'B' : 'A');
        assertThrows(
                InvitationNotFoundException.class,
                () -> queryService.findByToken(fixture.worker(), unknown)
        );
    }

    private String issueToken(InvitationIssueService issueService, Fixture fixture) {
        return tokenOf(issueService.issue(fixture.owner(), fixture.workCaseId)
                .getResponse()
                .getInviteUrl());
    }

    private static String tokenOf(String inviteUrl) {
        return inviteUrl.substring(inviteUrl.lastIndexOf('/') + 1);
    }

    private Fixture insertWorkCaseFixture(JdbcTemplate jdbcTemplate, String suffix) {
        Long ownerUserId = insertUser(jdbcTemplate, "qa155lo" + suffix, "OWNER", "생명주기사장");
        Long workerUserId = insertUser(jdbcTemplate, "qa155lw" + suffix, "WORKER", "생명주기알바");

        String businessNumber = String.format(
                "%010d", ThreadLocalRandom.current().nextLong(1_000_000_0000L));
        jdbcTemplate.update(
                "INSERT INTO workplaces (owner_user_id, business_registration_number, name, "
                        + "representative_name, road_address, phone) "
                        + "VALUES (?, ?, '강남점', '김사장', '서울 강남구 테헤란로 1', '0212345678')",
                ownerUserId,
                businessNumber);
        Long workplaceId = jdbcTemplate.queryForObject(
                "SELECT id FROM workplaces WHERE business_registration_number = ?",
                Long.class,
                businessNumber);

        LocalDateTime startsAt = LocalDateTime.now()
                .plusDays(30L)
                .truncatedTo(ChronoUnit.MICROS);
        jdbcTemplate.update(
                "INSERT INTO work_cases (employer_id, workplace_id, title, starts_at, ends_at, "
                        + "break_minutes, break_paid, workplace_name, workplace_address, "
                        + "agreed_wage, terms_version, status) "
                        + "VALUES (?, ?, '주말 홀 서빙', ?, ?, 60, 0, '강남점', "
                        + "'서울 강남구 테헤란로 1', 120000, 1, 'DRAFT')",
                ownerUserId,
                workplaceId,
                startsAt,
                startsAt.plusHours(8L));
        Long workCaseId = jdbcTemplate.queryForObject(
                "SELECT id FROM work_cases WHERE employer_id = ?", Long.class, ownerUserId);

        return new Fixture(ownerUserId, workerUserId, workplaceId, workCaseId, startsAt);
    }

    private Long insertUser(JdbcTemplate jdbcTemplate, String loginId, String role, String name) {
        jdbcTemplate.update(
                "INSERT INTO users (login_id, email, password_hash, name, role) "
                        + "VALUES (?, ?, ?, ?, ?)",
                loginId,
                loginId + "@example.com",
                "$2a$10$abcdefghijklmnopqrstuvwxyz0123456789ABCDEFGHIJKLMNOPQR",
                name,
                role);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM users WHERE login_id = ?", Long.class, loginId);
    }

    private void cleanUp(JdbcTemplate jdbcTemplate, Fixture fixture) {
        jdbcTemplate.update(
                "DELETE FROM work_invitations WHERE work_case_id = ?", fixture.workCaseId);
        jdbcTemplate.update("DELETE FROM notifications WHERE work_case_id = ?", fixture.workCaseId);
        jdbcTemplate.update("DELETE FROM work_cases WHERE id = ?", fixture.workCaseId);
        jdbcTemplate.update("DELETE FROM workplaces WHERE id = ?", fixture.workplaceId);
        // 초대 조회가 실제로 OWNER 배지를 재계산·Upsert하므로 users보다 먼저 지운다.
        jdbcTemplate.update(
                "DELETE FROM user_badges WHERE user_id IN (?, ?)",
                fixture.ownerUserId,
                fixture.workerUserId);
        jdbcTemplate.update(
                "DELETE FROM users WHERE id IN (?, ?)",
                fixture.ownerUserId,
                fixture.workerUserId);
    }

    /** 검증에 필요한 Fixture 식별자 묶음입니다. */
    private static final class Fixture {

        private final Long ownerUserId;
        private final Long workerUserId;
        private final Long workplaceId;
        private final Long workCaseId;
        private final LocalDateTime startsAt;

        private Fixture(
                Long ownerUserId,
                Long workerUserId,
                Long workplaceId,
                Long workCaseId,
                LocalDateTime startsAt) {
            this.ownerUserId = ownerUserId;
            this.workerUserId = workerUserId;
            this.workplaceId = workplaceId;
            this.workCaseId = workCaseId;
            this.startsAt = startsAt;
        }

        private AuthPrincipal owner() {
            return new AuthPrincipal(ownerUserId, UserRole.OWNER, "생명주기사장");
        }

        private AuthPrincipal worker() {
            return new AuthPrincipal(workerUserId, UserRole.WORKER, "생명주기알바");
        }
    }
}
