package com.gighub.attendance.service;

import com.gighub.attendance.dto.AttendanceScanConfirmationResponse;
import com.gighub.attendance.dto.AttendanceScanRequest;
import com.gighub.attendance.dto.AttendanceScanResponse;
import com.gighub.attendance.dto.AttendanceScanResult;
import com.gighub.attendance.exception.AttendanceScanException;
import com.gighub.attendance.qr.QrTokenCodec;
import com.gighub.attendance.service.result.AttendanceScanOutput;
import com.gighub.auth.security.AuthPrincipal;
import com.gighub.config.RootConfig;
import com.gighub.member.domain.UserRole;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 실제 MySQL 잠금·유니크 제약·트랜잭션 전파에서 근태 스캔의 동시성·멱등성을 검증합니다.
 *
 * <p>이 클래스는 {@link AttendanceScanServiceImpl}을 Mock 없이 실제 Spring Container에서
 * 조립해 사용합니다. 멱등 Claim 완료({@code claimService.complete}, {@code Propagation.MANDATORY})가
 * 실제 Transaction 경계 안에서 일어나는지는 Mock으로는 증명할 수 없고, 이 클래스처럼 진짜
 * Transaction Manager 위에서만 증명됩니다.</p>
 */
@Tag("database")
class AttendanceScanDatabaseIntegrationTest {

    private static final long WAGE = 300_000L;
    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
    private static final BigDecimal SITE_LATITUDE = new BigDecimal("37.5000000");
    private static final BigDecimal SITE_LONGITUDE = new BigDecimal("127.0000000");

    /**
     * 같은 QR을 향한 동시 CHECK_IN 스캔은 근태 행과 상태 전이를 정확히 한 번만 만듭니다.
     *
     * <p>모든 요청이 같은 {@code qr_tokens} 행을 {@code FOR UPDATE}로 잠그므로 실제 동시
     * 실행은 그 잠금 앞에서 직렬화됩니다. 첫 승자가 commit 되면 뒤이은 요청은 이미
     * {@code IN_PROGRESS}로 바뀐 최신 상태를 다시 읽으므로, 두 번째 CHECK_IN 대신 안전한
     * {@code CONFIRMATION_REQUIRED}나 승인된 충돌로 끝나야 합니다. 무엇으로 끝나든 근태
     * 행은 정확히 하나만 남아야 합니다.</p>
     */
    @Test
    @Timeout(30)
    void concurrentCheckInScansProduceExactlyOneSuccessfulAttendanceRow() throws Exception {
        try (AnnotationConfigApplicationContext context = applicationContext()) {
            JdbcTemplate jdbcTemplate = jdbcTemplate(context);
            LocalDateTime now = LocalDateTime.now(ZONE);
            Fixture fixture = createFixture(
                    context,
                    jdbcTemplate,
                    "READY",
                    now.minusMinutes(20),
                    now.plusHours(8),
                    true);
            AttendanceScanService scanService = context.getBean(AttendanceScanService.class);
            AuthPrincipal principal =
                    new AuthPrincipal(fixture.workerId(), UserRole.WORKER, "동시성 테스트");

            int concurrency = 12;
            List<Callable<Outcome>> attempts = new ArrayList<>();
            for (int i = 0; i < concurrency; i++) {
                String idempotencyKey = "SCAN-CONCURRENT-" + UUID.randomUUID();
                attempts.add(() -> attempt(
                        scanService,
                        principal,
                        idempotencyKey,
                        fixture.qrToken()));
            }

            try {
                List<Outcome> results = runConcurrently(attempts);

                long recorded = results.stream().filter(Outcome::isRecordedCheckIn).count();
                long safeOthers = results.stream()
                        .filter(outcome -> !outcome.isRecordedCheckIn())
                        .filter(Outcome::isSafe)
                        .count();

                assertEquals(1, recorded, "정확히 한 요청만 CHECK_IN을 기록해야 합니다.");
                assertEquals(concurrency - 1, safeOthers,
                        "나머지 요청은 중복 기록 없이 안전하게 끝나야 합니다.");

                Long successfulCheckIns = jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM attendance_records"
                                + " WHERE work_case_id = ? AND attendance_type = 'CHECK_IN'"
                                + " AND result = 'SUCCESS'",
                        Long.class,
                        fixture.workCaseId());
                assertEquals(1L, successfulCheckIns);

                String status = jdbcTemplate.queryForObject(
                        "SELECT status FROM work_cases WHERE id = ?",
                        String.class,
                        fixture.workCaseId());
                assertEquals("IN_PROGRESS", status);
            } finally {
                deleteFixture(jdbcTemplate, fixture);
            }
        }
    }

    /**
     * 같은 {@code Idempotency-Key}로 두 번 호출하면 두 번째는 저장된 응답을 그대로
     * Replay하고 근태 행을 다시 만들지 않습니다.
     *
     * <p>이 검증은 P0 회귀 방지가 목적입니다. 리뷰 이전 코드는
     * {@code claimService.complete()}를 판정 Transaction 밖에서 불러
     * {@code Propagation.MANDATORY}가 참여할 Transaction을 찾지 못해 모든 성공 응답이
     * 500으로 끝났습니다. Mock 기반 단위 테스트는 Transaction 전파를 검증할 수 없으므로,
     * 이 시나리오는 실제 Transaction Manager 위에서만 재발을 잡아낼 수 있습니다.</p>
     */
    @Test
    @Timeout(30)
    void sameIdempotencyKeyReplaysTheStoredCheckInWithoutASecondWrite() throws Exception {
        try (AnnotationConfigApplicationContext context = applicationContext()) {
            JdbcTemplate jdbcTemplate = jdbcTemplate(context);
            LocalDateTime now = LocalDateTime.now(ZONE);
            Fixture fixture = createFixture(
                    context,
                    jdbcTemplate,
                    "READY",
                    now.minusMinutes(20),
                    now.plusHours(8),
                    true);
            AttendanceScanService scanService = context.getBean(AttendanceScanService.class);
            AuthPrincipal principal =
                    new AuthPrincipal(fixture.workerId(), UserRole.WORKER, "재생 테스트");
            String idempotencyKey = "SCAN-REPLAY-" + UUID.randomUUID();

            // 같은 Body로 재시도해야 같은 요청으로 판정됩니다. capturedAt이 Fingerprint에
            // 들어가므로, 두 번째 호출에서 새로 만들면 다른 요청으로 거절됩니다
            // (IDEMPOTENCY_KEY_REUSED). 응답 유실 뒤 재시도는 client가 같은 Body를
            // 다시 보내는 상황을 뜻하므로 요청 객체 하나를 그대로 재사용합니다.
            AttendanceScanRequest request = scanRequest(fixture.qrToken());
            try {
                AttendanceScanOutput first = scanService.scan(principal, idempotencyKey, request);
                assertFalse(first.replayed());
                AttendanceScanResponse firstResponse = (AttendanceScanResponse) first.response();
                assertEquals("RECORDED", firstResponse.getResult());

                AttendanceScanOutput second = scanService.scan(principal, idempotencyKey, request);
                assertTrue(second.replayed());
                AttendanceScanResponse secondResponse = (AttendanceScanResponse) second.response();
                assertEquals(firstResponse.getRecordedAt(), secondResponse.getRecordedAt());

                Long checkInRows = jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM attendance_records"
                                + " WHERE work_case_id = ? AND attendance_type = 'CHECK_IN'",
                        Long.class,
                        fixture.workCaseId());
                assertEquals(1L, checkInRows, "Replay가 근태 행을 다시 만들면 안 됩니다.");
            } finally {
                deleteFixture(jdbcTemplate, fixture);
            }
        }
    }

    /**
     * 재발급으로 폐기된 QR은 근무 상태를 바꾸지 않고 승인된 오류로 거절됩니다.
     *
     * <p>승인된 잠금 순서 {@code workplaces -> qr_tokens -> work_cases}의 두 번째 단계가
     * 이 보장의 근거입니다. 재발급이 이미 commit되어 있으면, 뒤늦은 스캔은
     * {@code qr_tokens}를 잠근 시점에 이미 폐기된 최신 상태를 보게 됩니다.</p>
     */
    @Test
    @Timeout(30)
    void staleQrTokenAfterReissueIsRejectedWithoutWritingAnyAttendanceRow() throws Exception {
        try (AnnotationConfigApplicationContext context = applicationContext()) {
            JdbcTemplate jdbcTemplate = jdbcTemplate(context);
            LocalDateTime now = LocalDateTime.now(ZONE);
            Fixture fixture = createFixture(
                    context,
                    jdbcTemplate,
                    "READY",
                    now.minusMinutes(20),
                    now.plusHours(8),
                    true);
            String staleToken = fixture.qrToken();

            // 사업장 QR을 재발급합니다. 재발급은 옛 nonce를 폐기하고 새 nonce를 발급합니다.
            jdbcTemplate.update(
                    "UPDATE qr_tokens SET status = 'REVOKED', revoked_at = NOW(6)"
                            + " WHERE workplace_id = ? AND status = 'ACTIVE'",
                    fixture.workplaceId());
            byte[] newNonce = randomNonce();
            jdbcTemplate.update(
                    "INSERT INTO qr_tokens (workplace_id, issued_by_user_id, token_nonce, status)"
                            + " VALUES (?, ?, ?, 'ACTIVE')",
                    fixture.workplaceId(),
                    fixture.ownerId(),
                    newNonce);

            AttendanceScanService scanService = context.getBean(AttendanceScanService.class);
            AuthPrincipal principal =
                    new AuthPrincipal(fixture.workerId(), UserRole.WORKER, "재발급 테스트");

            try {
                AttendanceScanException failure = org.junit.jupiter.api.Assertions.assertThrows(
                        AttendanceScanException.class,
                        () -> scanService.scan(
                                principal,
                                "SCAN-STALE-QR-" + UUID.randomUUID(),
                                scanRequest(staleToken)));
                assertEquals(org.springframework.http.HttpStatus.GONE, failure.getStatus());

                Long attendanceRows = jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM attendance_records WHERE work_case_id = ?",
                        Long.class,
                        fixture.workCaseId());
                assertEquals(0L, attendanceRows);

                String status = jdbcTemplate.queryForObject(
                        "SELECT status FROM work_cases WHERE id = ?",
                        String.class,
                        fixture.workCaseId());
                assertEquals("READY", status);
            } finally {
                deleteFixture(jdbcTemplate, fixture);
            }
        }
    }

    // ---- 시도 실행 ----------------------------------------------------

    private Outcome attempt(
            AttendanceScanService scanService,
            AuthPrincipal principal,
            String idempotencyKey,
            String qrToken) {
        try {
            AttendanceScanOutput output =
                    scanService.scan(principal, idempotencyKey, scanRequest(qrToken));
            return Outcome.success(output.response());
        } catch (AttendanceScanException exception) {
            return Outcome.safeFailure(exception);
        }
    }

    private AttendanceScanRequest scanRequest(String qrToken) {
        return new AttendanceScanRequest(
                qrToken,
                SITE_LATITUDE,
                SITE_LONGITUDE,
                new BigDecimal("10.00"),
                Instant.now(),
                false);
    }

    private List<Outcome> runConcurrently(List<Callable<Outcome>> attempts) throws Exception {
        int size = attempts.size();
        ExecutorService pool = Executors.newFixedThreadPool(size);
        CountDownLatch ready = new CountDownLatch(size);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Outcome>> futures = new ArrayList<>();
            for (Callable<Outcome> attempt : attempts) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    if (!start.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("동시 스캔 시작 신호를 기다리지 못했습니다.");
                    }
                    return attempt.call();
                }));
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();

            List<Outcome> results = new ArrayList<>();
            for (Future<Outcome> future : futures) {
                results.add(future.get(15, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    // ---- 결과 판정 ------------------------------------------------------

    /** 동시 스캔 한 건의 결과입니다. 성공 응답 또는 승인된 예외로만 끝나야 합니다. */
    private record Outcome(AttendanceScanResult response, AttendanceScanException failure) {

        static Outcome success(AttendanceScanResult response) {
            return new Outcome(response, null);
        }

        static Outcome safeFailure(AttendanceScanException failure) {
            return new Outcome(null, failure);
        }

        boolean isRecordedCheckIn() {
            return response instanceof AttendanceScanResponse recorded
                    && "RECORDED".equals(recorded.getResult());
        }

        /**
         * 패자 쪽 결과가 데이터를 망가뜨리지 않는 형태인지 확인합니다.
         *
         * <p>이미 진행 중인 근무를 다시 본 요청은 조기 퇴근 확인을 되묻거나(성공 행을 만들지
         * 않음), 승인된 충돌 오류로 끝나야 합니다.</p>
         */
        boolean isSafe() {
            if (failure != null) {
                return true;
            }
            return response instanceof AttendanceScanConfirmationResponse;
        }
    }

    // ---- 고정물 ----------------------------------------------------------

    private record Fixture(
            long ownerId,
            long workerId,
            long workplaceId,
            long workCaseId,
            String qrToken) {
    }

    private AnnotationConfigApplicationContext applicationContext() {
        return new AnnotationConfigApplicationContext(RootConfig.class);
    }

    private JdbcTemplate jdbcTemplate(AnnotationConfigApplicationContext context) {
        return new JdbcTemplate(context.getBean(DataSource.class));
    }

    private Fixture createFixture(
            AnnotationConfigApplicationContext context,
            JdbcTemplate jdbcTemplate,
            String status,
            LocalDateTime startsAt,
            LocalDateTime endsAt,
            boolean needsSettlement) {
        String token = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String ownerLogin = "attscan_owner_" + token;
        String workerLogin = "attscan_worker_" + token;
        String businessNumber = String.format("%010d", Integer.toUnsignedLong(token.hashCode()));

        jdbcTemplate.update(
                "INSERT INTO users"
                        + " (login_id, email, password_hash, name, role, status)"
                        + " VALUES (?, ?, 'integration-test', '테스트 고용주', 'OWNER', 'ACTIVE')",
                ownerLogin,
                ownerLogin + "@example.invalid");
        jdbcTemplate.update(
                "INSERT INTO users"
                        + " (login_id, email, password_hash, name, role, status)"
                        + " VALUES (?, ?, 'integration-test', '테스트 근로자', 'WORKER', 'ACTIVE')",
                workerLogin,
                workerLogin + "@example.invalid");
        long ownerId = idBy(jdbcTemplate, "users", "login_id", ownerLogin);
        long workerId = idBy(jdbcTemplate, "users", "login_id", workerLogin);

        jdbcTemplate.update(
                "INSERT INTO workplaces"
                        + " (owner_user_id, business_registration_number, name,"
                        + " representative_name, road_address, detail_address, phone,"
                        + " latitude, longitude, radius_meters, status)"
                        + " VALUES (?, ?, '테스트 사업장', '테스트 고용주',"
                        + " '서울시 테스트로 1', NULL, '02-0000-0000', ?, ?, 100, 'ACTIVE')",
                ownerId,
                businessNumber,
                SITE_LATITUDE,
                SITE_LONGITUDE);
        long workplaceId = idBy(
                jdbcTemplate, "workplaces", "business_registration_number", businessNumber);

        String title = "ATTSCAN-" + token;
        jdbcTemplate.update(
                "INSERT INTO work_cases"
                        + " (employer_id, worker_id, workplace_id, title, starts_at, ends_at,"
                        + " break_minutes, break_paid, workplace_name, workplace_address,"
                        + " workplace_latitude, workplace_longitude, allowed_radius_meters,"
                        + " agreed_wage, terms_version, status)"
                        + " VALUES (?, ?, ?, ?, ?, ?, 60, 0, '테스트 사업장',"
                        + " '서울시 테스트로 1', ?, ?, 100, ?, 1, ?)",
                ownerId,
                workerId,
                workplaceId,
                title,
                startsAt,
                endsAt,
                SITE_LATITUDE,
                SITE_LONGITUDE,
                WAGE,
                status);
        long workCaseId = idBy(jdbcTemplate, "work_cases", "title", title);

        if (needsSettlement) {
            jdbcTemplate.update(
                    "INSERT INTO settlements (work_case_id, amount, status, due_at)"
                            + " VALUES (?, ?, 'WAITING', NULL)",
                    workCaseId,
                    WAGE);
        }

        byte[] nonce = randomNonce();
        jdbcTemplate.update(
                "INSERT INTO qr_tokens (workplace_id, issued_by_user_id, token_nonce, status)"
                        + " VALUES (?, ?, ?, 'ACTIVE')",
                workplaceId,
                ownerId,
                nonce);

        String qrToken = context.getBean(QrTokenCodec.class).sign(workplaceId, nonce);

        return new Fixture(ownerId, workerId, workplaceId, workCaseId, qrToken);
    }

    private void deleteFixture(JdbcTemplate jdbcTemplate, Fixture fixture) {
        jdbcTemplate.update(
                "DELETE FROM idempotency_requests WHERE user_id = ?", fixture.workerId());
        jdbcTemplate.update(
                "DELETE FROM attendance_records WHERE work_case_id = ?", fixture.workCaseId());
        jdbcTemplate.update(
                "DELETE FROM settlements WHERE work_case_id = ?", fixture.workCaseId());
        jdbcTemplate.update("DELETE FROM qr_tokens WHERE workplace_id = ?", fixture.workplaceId());
        jdbcTemplate.update("DELETE FROM work_cases WHERE id = ?", fixture.workCaseId());
        jdbcTemplate.update("DELETE FROM workplaces WHERE id = ?", fixture.workplaceId());
        jdbcTemplate.update(
                "DELETE FROM users WHERE id IN (?, ?)", fixture.ownerId(), fixture.workerId());
    }

    private long idBy(JdbcTemplate jdbcTemplate, String table, String column, Object value) {
        Long id = jdbcTemplate.queryForObject(
                "SELECT id FROM " + table + " WHERE " + column + " = ?", Long.class, value);
        if (id == null) {
            throw new IllegalStateException("생성된 식별자를 찾지 못했습니다: " + table);
        }
        return id;
    }

    private static byte[] randomNonce() {
        byte[] nonce = new byte[16];
        new SecureRandom().nextBytes(nonce);
        return nonce;
    }
}
