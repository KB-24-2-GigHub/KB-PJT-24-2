package com.gighub.invitation.mapper;

import com.gighub.invitation.domain.InvitationStatus;
import com.gighub.config.RootConfig;
import com.gighub.invitation.config.InvitationProperties;
import com.gighub.invitation.mapper.param.InvitationInsertParam;
import com.gighub.invitation.mapper.result.InvitationRow;
import com.gighub.invitation.token.InvitationTokenCodec;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 실제 MySQL Head Schema에서 초대 저장과 상태 전이 계약을 검증합니다.
 *
 * <p>활성 초대 Unique, Token Hash Unique, 생성 Column 동작과 동시 발급은 Java 단위 검증으로
 * 대신할 수 없어 {@code database} Tag의 Opt-in Test로 둡니다.</p>
 *
 * <p>Work Case는 #154의 Java 계층 대신 SQL Fixture로 직접 만듭니다. 초대 계약이 다른 이슈의
 * 진행 상황에 묶이지 않게 하기 위해서입니다.</p>
 */
@Tag("database")
class InvitationMapperDatabaseIntegrationTest {

    private static final String SECRET = "database-test-invitation-secret-0123456789";
    private static final String WEB_ORIGIN = "http://localhost:5173";

    private final InvitationTokenCodec codec = new InvitationTokenCodec(
            InvitationProperties.of(SECRET, null, WEB_ORIGIN)
    );

    @Test
    @Timeout(60)
    void storesOnlyTokenHashAndKeepsOneActiveInvitationPerWorkCase() throws Exception {
        try (AnnotationConfigApplicationContext context =
                     new AnnotationConfigApplicationContext(RootConfig.class)) {
            JdbcTemplate jdbcTemplate = new JdbcTemplate(context.getBean(DataSource.class));
            InvitationMapper invitationMapper = context.getBean(InvitationMapper.class);

            String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
            Fixture fixture = insertWorkCaseFixture(jdbcTemplate, suffix);

            try {
                verifyDerivedTokenIsStoredAsHashOnly(jdbcTemplate, invitationMapper, fixture);
                verifySecondActiveInvitationIsRejected(invitationMapper, fixture);
                verifyExpiryFreesTheActiveSlot(jdbcTemplate, invitationMapper, fixture);
                verifyRevokePreservesHistory(jdbcTemplate, invitationMapper, fixture);
                verifyTerminalStatesAreNotOverwrittenByExpiry(jdbcTemplate, invitationMapper, fixture);
                verifyConcurrentIssueLeavesSingleActiveInvitation(jdbcTemplate, invitationMapper, fixture);
            } finally {
                cleanUp(jdbcTemplate, fixture);
            }
        }
    }

    /**
     * 발급 흐름 그대로 임시 Hash로 행을 만든 뒤 파생 Token의 Hash로 갱신하고, 어떤 Column에도
     * 원문이 남지 않는지 확인합니다.
     */
    private void verifyDerivedTokenIsStoredAsHashOnly(
            JdbcTemplate jdbcTemplate,
            InvitationMapper invitationMapper,
            Fixture fixture) {
        LocalDateTime expiresAt = fixture.startsAt;
        InvitationInsertParam param = InvitationInsertParam.builder()
                .workCaseId(fixture.workCaseId)
                .expectedTermsVersion(1)
                .expiresAt(expiresAt)
                .build();

        assertEquals(1, invitationMapper.insertPending(param));
        assertNotNull(param.getId(), "생성 Key가 파라미터로 되돌아와야 합니다.");

        byte[] placeholderHash = (byte[]) jdbcTemplate
                .queryForMap("SELECT token_hash FROM work_invitations WHERE id = ?", param.getId())
                .get("token_hash");
        assertEquals(32, placeholderHash.length, "임시 Hash도 BINARY(32)여야 합니다.");

        String token = codec.deriveToken(param.getId());
        assertEquals(1, invitationMapper.updateTokenHash(param.getId(), codec.hash(token)));

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT * FROM work_invitations WHERE id = ?", param.getId());

        assertEquals("PENDING", row.get("status"));
        assertEquals(fixture.workCaseId, ((Number) row.get("work_case_id")).longValue());
        assertEquals(1, ((Number) row.get("expected_terms_version")).intValue());
        assertEquals(expiresAt, toLocalDateTime(row.get("expires_at")));
        assertNull(row.get("accepted_by_user_id"));
        assertNull(row.get("revoked_at"));
        // PENDING인 동안에만 생성 Column이 값을 가져 활성 초대 Unique가 걸립니다.
        assertEquals(1, ((Number) row.get("active_slot")).intValue());

        for (Map.Entry<String, Object> column : row.entrySet()) {
            Object value = column.getValue();
            if (value instanceof String) {
                assertFalse(
                        ((String) value).contains(token),
                        column.getKey() + " Column에 Token 원문이 저장되면 안 됩니다."
                );
            }
        }

        InvitationRow found = invitationMapper.findByTokenHashForUpdate(codec.hash(token));
        assertNotNull(found, "저장된 Hash로 초대를 찾을 수 있어야 합니다.");
        assertEquals(param.getId(), found.getId());
        assertEquals(InvitationStatus.PENDING, found.getStatus());
        assertTrue(codec.matches(token, found.getTokenHash()));

        assertNull(
                invitationMapper.findByTokenHashForUpdate(codec.hash(codec.deriveToken(999_999L))),
                "다른 Token의 Hash로는 조회되지 않아야 합니다."
        );
    }

    /** 활성 PENDING이 있는 동안에는 같은 Work Case에 두 번째 초대를 만들 수 없어야 합니다. */
    private void verifySecondActiveInvitationIsRejected(
            InvitationMapper invitationMapper,
            Fixture fixture) {
        InvitationInsertParam duplicate = InvitationInsertParam.builder()
                .workCaseId(fixture.workCaseId)
                .expectedTermsVersion(1)
                .expiresAt(fixture.startsAt)
                .build();

        assertThrows(DuplicateKeyException.class, () -> invitationMapper.insertPending(duplicate));
    }

    /** 만료 전이가 활성 Slot을 비워 재발급이 가능해져야 합니다. */
    private void verifyExpiryFreesTheActiveSlot(
            JdbcTemplate jdbcTemplate,
            InvitationMapper invitationMapper,
            Fixture fixture) {
        InvitationRow active =
                invitationMapper.findActivePendingByWorkCaseIdForUpdate(fixture.workCaseId);
        assertNotNull(active);

        // 만료 시각 직전에는 아직 유효합니다.
        assertEquals(
                0,
                invitationMapper.expireOverduePending(
                        fixture.workCaseId, fixture.startsAt.minusNanos(1_000L))
        );
        assertEquals(
                "PENDING",
                jdbcTemplate.queryForObject(
                        "SELECT status FROM work_invitations WHERE id = ?",
                        String.class,
                        active.getId())
        );

        // 만료 시각과 정확히 같은 순간부터 사용할 수 없습니다.
        assertEquals(
                1,
                invitationMapper.expireOverduePending(fixture.workCaseId, fixture.startsAt)
        );
        assertEquals(
                "EXPIRED",
                jdbcTemplate.queryForObject(
                        "SELECT status FROM work_invitations WHERE id = ?",
                        String.class,
                        active.getId())
        );
        assertNull(
                jdbcTemplate.queryForMap(
                        "SELECT active_slot FROM work_invitations WHERE id = ?", active.getId())
                        .get("active_slot"),
                "종료된 초대는 활성 Slot을 차지하지 않아야 합니다."
        );

        assertNull(invitationMapper.findActivePendingByWorkCaseIdForUpdate(fixture.workCaseId));

        InvitationInsertParam reissued = InvitationInsertParam.builder()
                .workCaseId(fixture.workCaseId)
                .expectedTermsVersion(1)
                .expiresAt(fixture.startsAt)
                .build();
        assertEquals(1, invitationMapper.insertPending(reissued));
        assertEquals(
                1,
                invitationMapper.updateTokenHash(
                        reissued.getId(), codec.hash(codec.deriveToken(reissued.getId())))
        );
    }

    /** 철회는 행을 지우지 않고 상태와 시각으로 남아야 합니다. */
    private void verifyRevokePreservesHistory(
            JdbcTemplate jdbcTemplate,
            InvitationMapper invitationMapper,
            Fixture fixture) {
        InvitationRow active =
                invitationMapper.findActivePendingByWorkCaseIdForUpdate(fixture.workCaseId);
        assertNotNull(active);

        LocalDateTime revokedAt = fixture.startsAt.minusDays(1L);
        assertEquals(1, invitationMapper.revokePendingByWorkCaseId(fixture.workCaseId, revokedAt));

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT status, revoked_at FROM work_invitations WHERE id = ?", active.getId());
        assertEquals("REVOKED", row.get("status"));
        assertEquals(revokedAt, toLocalDateTime(row.get("revoked_at")));

        // 활성 초대가 없으면 철회할 대상도 없습니다.
        assertEquals(0, invitationMapper.revokePendingByWorkCaseId(fixture.workCaseId, revokedAt));
    }

    /** 이미 종료된 초대를 만료가 덮어써 종료 사유를 지우면 안 됩니다. */
    private void verifyTerminalStatesAreNotOverwrittenByExpiry(
            JdbcTemplate jdbcTemplate,
            InvitationMapper invitationMapper,
            Fixture fixture) {
        Long revokedId = jdbcTemplate.queryForObject(
                "SELECT id FROM work_invitations WHERE work_case_id = ? AND status = 'REVOKED'",
                Long.class,
                fixture.workCaseId);

        assertEquals(0, invitationMapper.markExpired(revokedId));
        assertEquals(
                0,
                invitationMapper.expireOverduePending(fixture.workCaseId, fixture.startsAt)
        );
        assertEquals(
                "REVOKED",
                jdbcTemplate.queryForObject(
                        "SELECT status FROM work_invitations WHERE id = ?",
                        String.class,
                        revokedId)
        );
    }

    /**
     * 사전 조회로는 막을 수 없는 경로입니다. 두 요청이 동시에 "활성 초대 없음"을 확인해도
     * 활성 PENDING이 하나만 남는지 확인합니다.
     */
    private void verifyConcurrentIssueLeavesSingleActiveInvitation(
            JdbcTemplate jdbcTemplate,
            InvitationMapper invitationMapper,
            Fixture fixture) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            List<Future<Boolean>> results = List.of(
                    executor.submit(() -> attemptIssue(invitationMapper, fixture, start)),
                    executor.submit(() -> attemptIssue(invitationMapper, fixture, start))
            );

            start.countDown();

            int issued = 0;
            for (Future<Boolean> result : results) {
                if (Boolean.TRUE.equals(result.get(20L, TimeUnit.SECONDS))) {
                    issued++;
                }
            }

            assertEquals(1, issued, "동시 발급에서 한 건만 성공해야 합니다.");
            assertEquals(
                    1,
                    jdbcTemplate.queryForObject(
                            "SELECT COUNT(*) FROM work_invitations "
                                    + "WHERE work_case_id = ? AND status = 'PENDING'",
                            Integer.class,
                            fixture.workCaseId)
            );
        } finally {
            executor.shutdownNow();
        }
    }

    private boolean attemptIssue(
            InvitationMapper invitationMapper,
            Fixture fixture,
            CountDownLatch start) throws InterruptedException {
        start.await();
        try {
            InvitationInsertParam param = InvitationInsertParam.builder()
                    .workCaseId(fixture.workCaseId)
                    .expectedTermsVersion(1)
                    .expiresAt(fixture.startsAt)
                    .build();
            invitationMapper.insertPending(param);
            invitationMapper.updateTokenHash(
                    param.getId(), codec.hash(codec.deriveToken(param.getId())));
            return true;
        } catch (DuplicateKeyException expected) {
            return false;
        }
    }

    /** DATETIME(6) Column의 Driver 표현이 Timestamp든 LocalDateTime이든 같게 비교합니다. */
    private static LocalDateTime toLocalDateTime(Object value) {
        if (value instanceof LocalDateTime) {
            return (LocalDateTime) value;
        }
        return ((java.sql.Timestamp) value).toLocalDateTime();
    }

    private Fixture insertWorkCaseFixture(JdbcTemplate jdbcTemplate, String suffix) {
        jdbcTemplate.update(
                "INSERT INTO users (login_id, email, password_hash, name, role) "
                        + "VALUES (?, ?, ?, ?, 'OWNER')",
                "qa155" + suffix,
                "qa155" + suffix + "@example.com",
                "$2a$10$abcdefghijklmnopqrstuvwxyz0123456789ABCDEFGHIJKLMNOPQR",
                "초대검증사장");
        Long ownerUserId = jdbcTemplate.queryForObject(
                "SELECT id FROM users WHERE login_id = ?", Long.class, "qa155" + suffix);

        // 사업자등록번호는 Unique 숫자 10자리이므로 실행마다 다른 값을 만듭니다.
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

        // 만료 시각 경계를 다루므로 초 미만까지 고정된 값을 씁니다.
        LocalDateTime startsAt = LocalDateTime.of(2026, 9, 12, 10, 0, 0);
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

        return new Fixture(ownerUserId, workplaceId, workCaseId, startsAt);
    }

    private void cleanUp(JdbcTemplate jdbcTemplate, Fixture fixture) {
        jdbcTemplate.update(
                "DELETE FROM work_invitations WHERE work_case_id = ?", fixture.workCaseId);
        jdbcTemplate.update("DELETE FROM work_cases WHERE id = ?", fixture.workCaseId);
        jdbcTemplate.update("DELETE FROM workplaces WHERE id = ?", fixture.workplaceId);
        jdbcTemplate.update("DELETE FROM users WHERE id = ?", fixture.ownerUserId);
    }

    /** 검증에 필요한 Fixture 식별자 묶음입니다. */
    private static final class Fixture {

        private final Long ownerUserId;
        private final Long workplaceId;
        private final Long workCaseId;
        private final LocalDateTime startsAt;

        private Fixture(Long ownerUserId, Long workplaceId, Long workCaseId, LocalDateTime startsAt) {
            this.ownerUserId = ownerUserId;
            this.workplaceId = workplaceId;
            this.workCaseId = workCaseId;
            this.startsAt = startsAt;
        }
    }
}
