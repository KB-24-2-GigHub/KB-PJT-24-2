package com.gighub.work.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import com.gighub.auth.security.AuthPrincipal;
import com.gighub.common.exception.ResourceNotFoundException;
import com.gighub.common.exception.WorkCaseLockedException;
import com.gighub.config.RootConfig;
import com.gighub.member.domain.UserRole;
import com.gighub.work.dto.WorkCaseDetailResponse;
import com.gighub.work.dto.WorkCaseSummaryResponse;
import com.gighub.work.service.command.WorkCaseCreateCommand;
import com.gighub.work.service.command.WorkCaseUpdateCommand;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 실제 MySQL Head Schema와 Spring Transaction Proxy 위에서 {@link WorkCaseService} 계약을
 * 검증합니다.
 *
 * <p>{@link WorkCaseServiceImplTest 단위테스트}는 Mapper를 Mockito로 대체해 순수 로직만
 * 검증하고, {@code WorkCaseMapperDatabaseIntegrationTest}는 개별 SQL만 검증합니다. 이 Test는
 * 둘 다 못 보는 지점 — {@code @Service} Bean 조립, {@code @Transactional} Proxy 적용,
 * {@link com.gighub.work.domain.WorkCaseTimes}·{@link com.gighub.work.domain.WorkCaseAddress}를
 * 거친 실제 값이 DB Round Trip에서 그대로 맞는지 — 를 확인합니다.
 *
 * <p>{@code database} Tag의 Opt-in Test이며 {@code ./gradlew databaseTest}로만 실행합니다.</p>
 */
@Tag("database")
class WorkCaseServiceDatabaseIntegrationTest {

    private static final BigDecimal LATITUDE = new BigDecimal("37.1234567");
    private static final BigDecimal LONGITUDE = new BigDecimal("127.1234567");

    @Test
    @Timeout(60)
    void appliesWorkCaseWriteContractOnCurrentMysqlSchema() throws Exception {
        try (AnnotationConfigApplicationContext context =
                     new AnnotationConfigApplicationContext(RootConfig.class)) {
            JdbcTemplate jdbc = new JdbcTemplate(context.getBean(DataSource.class));
            WorkCaseService service = context.getBean(WorkCaseService.class);

            String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
            String businessNumber = String.format(
                    "%010d", ThreadLocalRandom.current().nextLong(0, 1_000_000_0000L));

            Long ownerId = insertOwner(jdbc, "qa154w" + suffix);
            Long otherOwnerId = insertOwner(jdbc, "qa154x" + suffix);
            Long workplaceId = insertWorkplace(jdbc, ownerId, businessNumber);
            Long detailWorkerUserId = null;

            try {
                Long workCaseId = verifyCreateStoresRealAddressAndTimes(
                        service, jdbc, ownerId, workplaceId);
                verifyUpdateBumpsVersionAndRevokesPendingInvitation(
                        service, jdbc, ownerId, workCaseId);
                verifyOwnershipIsolation(service, otherOwnerId, workCaseId);

                Long concurrentId = createDraft(service, ownerId, workplaceId);
                verifyConcurrentUpdatesAreSerializedWithoutLostUpdate(
                        service, jdbc, ownerId, concurrentId);

                Long deletableId = createDraft(service, ownerId, workplaceId);
                verifyDeleteHardDeletesWithoutInvitationHistory(service, jdbc, ownerId, deletableId);

                Long cancelableId = createDraft(service, ownerId, workplaceId);
                verifyDeleteCancelsWithInvitationHistory(service, jdbc, ownerId, cancelableId);

                verifySummaryReflectsCurrentStatuses(service, ownerId, otherOwnerId, workplaceId);
                detailWorkerUserId = verifyDetailAggregatesAllDomains(
                        jdbc, service, ownerId, otherOwnerId, workplaceId);
            } finally {
                cleanUp(jdbc, ownerId, otherOwnerId);
                // work_contracts·attendance_records가 이 WORKER를 참조하므로 위 cleanUp이
                // 그 자식 행을 먼저 지운 뒤에만 안전하게 지울 수 있습니다.
                if (detailWorkerUserId != null) {
                    jdbc.update("DELETE FROM users WHERE id = ?", detailWorkerUserId);
                }
            }
        }
    }

    /** Service가 만든 combine 결과가 Mock이 아니라 실제 DB 값으로 저장·조회되는지 확인합니다. */
    private Long verifyCreateStoresRealAddressAndTimes(
            WorkCaseService service,
            JdbcTemplate jdbc,
            Long ownerId,
            Long workplaceId) {
        Long workCaseId = createDraft(service, ownerId, workplaceId);

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT workplace_address, starts_at, ends_at, terms_version, status"
                        + " FROM work_cases WHERE id = ?",
                workCaseId);

        assertEquals("서울 강남구 테헤란로 1 2층", row.get("workplace_address"));
        // Connector/J가 DATETIME을 시간대 없는 LocalDateTime으로 그대로 돌려줍니다.
        assertEquals(LocalDateTime.of(2026, 8, 10, 9, 0), row.get("starts_at"));
        assertEquals(LocalDateTime.of(2026, 8, 10, 18, 0), row.get("ends_at"));
        assertEquals(1, ((Number) row.get("terms_version")).intValue());
        assertEquals("DRAFT", row.get("status"));
        return workCaseId;
    }

    /** update()가 잠금·조건 교체·PENDING 초대 철회를 한 Transaction으로 실제 반영하는지 확인합니다. */
    private void verifyUpdateBumpsVersionAndRevokesPendingInvitation(
            WorkCaseService service,
            JdbcTemplate jdbc,
            Long ownerId,
            Long workCaseId) {
        insertInvitation(jdbc, workCaseId, "PENDING");

        service.update(owner(ownerId), WorkCaseUpdateCommand.builder()
                .workCaseId(workCaseId)
                .title("주말 홀 서빙(수정)")
                .workDate(LocalDate.of(2026, 8, 10))
                .startTime(LocalTime.of(10, 0))
                .endTime(LocalTime.of(19, 0))
                .breakMinutes(30)
                .breakPaid(true)
                .dailyWage(130_000L)
                .build());

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT title, terms_version FROM work_cases WHERE id = ?", workCaseId);
        assertEquals("주말 홀 서빙(수정)", row.get("title"));
        assertEquals(2, ((Number) row.get("terms_version")).intValue());

        assertEquals(
                0,
                countInvitations(jdbc, workCaseId, "PENDING"),
                "조건 변경 뒤에는 PENDING 초대가 남아 있으면 안 됩니다.");
        assertEquals(1, countInvitations(jdbc, workCaseId, "REVOKED"));

        // DRAFT가 아니면 같은 요청이 WORK_CASE_LOCKED로 거절되는지 같은 흐름에서 확인합니다.
        jdbc.update(
                "UPDATE work_cases SET status = 'CANCELED', canceled_at = CURRENT_TIMESTAMP(6)"
                        + " WHERE id = ?",
                workCaseId);
        assertThrows(
                WorkCaseLockedException.class,
                () -> service.update(owner(ownerId), WorkCaseUpdateCommand.builder()
                        .workCaseId(workCaseId)
                        .title("재수정 시도")
                        .workDate(LocalDate.of(2026, 8, 10))
                        .startTime(LocalTime.of(10, 0))
                        .endTime(LocalTime.of(19, 0))
                        .breakMinutes(30)
                        .breakPaid(true)
                        .dailyWage(130_000L)
                        .build()));
    }

    /**
     * write 시나리오가 만든 실제 상태 분포로 Summary를 검증합니다. 이 시점엔
     * {@code workCaseId}=CANCELED, {@code concurrentId}=DRAFT, {@code deletableId}=삭제됨,
     * {@code cancelableId}=CANCELED가 남아 있어야 합니다.
     */
    private void verifySummaryReflectsCurrentStatuses(
            WorkCaseService service,
            Long ownerId,
            Long otherOwnerId,
            Long workplaceId) {
        WorkCaseSummaryResponse summary = service.summary(owner(ownerId), workplaceId);

        assertEquals(1, summary.getDraft());
        assertEquals(2, summary.getCanceled());
        assertEquals(0, summary.getCompleted());

        assertThrows(
                ResourceNotFoundException.class,
                () -> service.summary(owner(otherOwnerId), workplaceId));
    }

    /**
     * 계약·문서·에스크로·정산·근태·초대를 실제 여러 테이블에 심어 상세 조회가 이들을 하나의
     * Aggregate로 정확히 합치는지 확인합니다. 계약은 있는데 연결 문서가 없는 손상 상태와,
     * 당사자가 아닌 접근이 거부되는지도 함께 봅니다.
     */
    private Long verifyDetailAggregatesAllDomains(
            JdbcTemplate jdbc,
            WorkCaseService service,
            Long ownerId,
            Long otherOwnerId,
            Long workplaceId) {
        Long workerUserId = insertWorker(jdbc, "qa154d1" + UUID.randomUUID().toString().substring(0, 8));

        // 출근 전 근무는 근태 기록이 없다. 집계 SQL이 전 컬럼 NULL 행을 돌려주고 MyBatis가 그
        // 행을 null 객체로 매핑하므로, 이 경로를 함께 검증하지 않으면 상세 조회가 상시 실패해도
        // 아래의 "기록이 다 있는" 검증만으로는 드러나지 않는다.
        Long beforeAttendanceId =
                insertMatchedWorkCase(jdbc, ownerId, workplaceId, workerUserId, "ACCEPTED");
        WorkCaseDetailResponse beforeAttendance =
                service.detail(owner(ownerId), beforeAttendanceId);
        assertNotNull(beforeAttendance.getAttendance());
        assertNull(beforeAttendance.getAttendance().getCheckedInAt());
        assertNull(beforeAttendance.getAttendance().getCheckedOutAt());

        Long workCaseId = insertMatchedWorkCase(jdbc, ownerId, workplaceId, workerUserId, "COMPLETED");

        insertInvitation(jdbc, workCaseId, "ACCEPTED");
        Long documentId = insertEmploymentContractDocument(jdbc, ownerId, workCaseId);
        insertContract(jdbc, workCaseId, ownerId, workerUserId);
        insertEscrow(jdbc, workCaseId, "RELEASED");
        insertSettlement(jdbc, workCaseId, "COMPLETED");
        insertAttendance(jdbc, workCaseId, workerUserId, "CHECK_IN");
        insertAttendance(jdbc, workCaseId, workerUserId, "CHECK_OUT");

        WorkCaseDetailResponse asOwner = service.detail(owner(ownerId), workCaseId);
        assertEquals(workCaseId, asOwner.getWorkCaseId());
        assertNotNull(asOwner.getWorker());
        assertEquals(workerUserId, asOwner.getWorker().getWorkerId());
        assertNotNull(asOwner.getLatestInvitation());
        assertEquals("ACCEPTED", asOwner.getLatestInvitation().getStatus());
        assertNotNull(asOwner.getContract());
        assertEquals(documentId, asOwner.getContract().getDocumentId());
        assertNotNull(asOwner.getEscrow());
        assertEquals("RELEASED", asOwner.getEscrow().getStatus());
        assertNotNull(asOwner.getSettlement());
        assertEquals("COMPLETED", asOwner.getSettlement().getStatus());
        assertNotNull(asOwner.getAttendance().getCheckedInAt());
        assertNotNull(asOwner.getAttendance().getCheckedOutAt());

        WorkCaseDetailResponse asWorker =
                service.detail(new AuthPrincipal(workerUserId, UserRole.WORKER, "이알바"), workCaseId);
        assertEquals(workCaseId, asWorker.getWorkCaseId());

        assertThrows(
                ResourceNotFoundException.class,
                () -> service.detail(owner(otherOwnerId), workCaseId));

        // 계약은 있는데 연결 문서가 없는 손상 상태는 500으로 이어지는 예외를 던져야 합니다.
        Long brokenWorkCaseId = insertMatchedWorkCase(jdbc, ownerId, workplaceId, workerUserId, "COMPLETED");
        insertContract(jdbc, brokenWorkCaseId, ownerId, workerUserId);
        assertThrows(
                IllegalStateException.class,
                () -> service.detail(owner(ownerId), brokenWorkCaseId));

        return workerUserId;
    }

    /** 다른 OWNER는 존재 여부를 알 수 없도록 404로만 응답받습니다. */
    private void verifyOwnershipIsolation(WorkCaseService service, Long otherOwnerId, Long workCaseId) {
        assertThrows(
                ResourceNotFoundException.class,
                () -> service.delete(owner(otherOwnerId), workCaseId));
    }

    /**
     * {@code lockById}의 {@code FOR UPDATE}가 실제 동시 요청을 직렬화하는지 확인합니다.
     *
     * <p>여러 스레드가 같은 DRAFT를 동시에 수정해도 {@code terms_version = terms_version + 1}은
     * 읽은 값을 그대로 되쓰지 않고 매번 현재 값에 더합니다. 잠금이 걸리지 않으면 두 요청이 같은
     * 이전 값을 동시에 읽어 증가분 하나가 사라지는 Lost Update가 생깁니다. 최종 Version이
     * 시작값+요청 수와 정확히 같아야 아무 증가분도 유실되지 않은 것입니다.</p>
     */
    private void verifyConcurrentUpdatesAreSerializedWithoutLostUpdate(
            WorkCaseService service,
            JdbcTemplate jdbc,
            Long ownerId,
            Long workCaseId) throws Exception {
        int concurrentRequests = 5;
        int startingVersion = currentTermsVersion(jdbc, workCaseId);

        ExecutorService executor = Executors.newFixedThreadPool(concurrentRequests);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<?>> results = new ArrayList<>();
            for (int index = 0; index < concurrentRequests; index++) {
                results.add(executor.submit(
                        () -> attemptConcurrentUpdate(service, ownerId, workCaseId, start)));
            }

            start.countDown();

            for (Future<?> result : results) {
                result.get(20, TimeUnit.SECONDS);
            }

            assertEquals(
                    startingVersion + concurrentRequests,
                    currentTermsVersion(jdbc, workCaseId),
                    "동시 요청이 모두 반영되어야 하며 Lost Update가 있으면 안 됩니다.");
        } finally {
            executor.shutdownNow();
        }
    }

    private void attemptConcurrentUpdate(
            WorkCaseService service,
            Long ownerId,
            Long workCaseId,
            CountDownLatch start) {
        try {
            start.await();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return;
        }

        service.update(owner(ownerId), WorkCaseUpdateCommand.builder()
                .workCaseId(workCaseId)
                .title("동시 수정 시도")
                .workDate(LocalDate.of(2026, 8, 10))
                .startTime(LocalTime.of(9, 0))
                .endTime(LocalTime.of(18, 0))
                .breakMinutes(60)
                .breakPaid(false)
                .dailyWage(120_000L)
                .build());
    }

    private int currentTermsVersion(JdbcTemplate jdbc, Long workCaseId) {
        return jdbc.queryForObject(
                "SELECT terms_version FROM work_cases WHERE id = ?", Integer.class, workCaseId);
    }

    private void verifyDeleteHardDeletesWithoutInvitationHistory(
            WorkCaseService service,
            JdbcTemplate jdbc,
            Long ownerId,
            Long workCaseId) {
        service.delete(owner(ownerId), workCaseId);

        Integer remaining = jdbc.queryForObject(
                "SELECT COUNT(*) FROM work_cases WHERE id = ?", Integer.class, workCaseId);
        assertEquals(0, remaining);
    }

    private void verifyDeleteCancelsWithInvitationHistory(
            WorkCaseService service,
            JdbcTemplate jdbc,
            Long ownerId,
            Long workCaseId) {
        insertInvitation(jdbc, workCaseId, "PENDING");

        service.delete(owner(ownerId), workCaseId);

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT status, canceled_at FROM work_cases WHERE id = ?", workCaseId);
        assertEquals("CANCELED", row.get("status"));
        assertNotNull(row.get("canceled_at"));
        assertEquals(0, countInvitations(jdbc, workCaseId, "PENDING"));
    }

    private Long createDraft(WorkCaseService service, Long ownerId, Long workplaceId) {
        return service.create(owner(ownerId), WorkCaseCreateCommand.builder()
                .workplaceId(workplaceId)
                .title("주말 홀 서빙")
                .workDate(LocalDate.of(2026, 8, 10))
                .startTime(LocalTime.of(9, 0))
                .endTime(LocalTime.of(18, 0))
                .breakMinutes(60)
                .breakPaid(false)
                .dailyWage(120_000L)
                .build());
    }

    private AuthPrincipal owner(Long ownerId) {
        return new AuthPrincipal(ownerId, UserRole.OWNER, "김사장");
    }

    private int countInvitations(JdbcTemplate jdbc, Long workCaseId, String status) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM work_invitations WHERE work_case_id = ? AND status = ?",
                Integer.class, workCaseId, status);
    }

    private void insertInvitation(JdbcTemplate jdbc, Long workCaseId, String status) {
        jdbc.update(
                "INSERT INTO work_invitations"
                        + " (work_case_id, token_hash, status, expected_terms_version, expires_at)"
                        + " VALUES (?, UNHEX(SHA2(CONCAT(?, RAND()), 256)), ?, 1,"
                        + " DATE_ADD(CURRENT_TIMESTAMP(6), INTERVAL 1 DAY))",
                workCaseId, workCaseId, status);
    }

    private Long insertOwner(JdbcTemplate jdbc, String loginId) {
        jdbc.update(
                "INSERT INTO users (login_id, email, password_hash, name, role)"
                        + " VALUES (?, ?, ?, ?, 'OWNER')",
                loginId,
                loginId + "@example.com",
                "$2a$10$0000000000000000000000000000000000000000000000000000",
                "김사장");
        return jdbc.queryForObject("SELECT id FROM users WHERE login_id = ?", Long.class, loginId);
    }

    private Long insertWorkplace(JdbcTemplate jdbc, Long ownerId, String businessNumber) {
        jdbc.update(
                "INSERT INTO workplaces (owner_user_id, business_registration_number, name,"
                        + " representative_name, road_address, detail_address, phone,"
                        + " latitude, longitude, radius_meters, status)"
                        + " VALUES (?, ?, '강남점', '김사장', '  서울 강남구 테헤란로 1  ', '  2층  ',"
                        + " '0212345678', ?, ?, 100.00, 'ACTIVE')",
                ownerId, businessNumber, LATITUDE, LONGITUDE);
        return jdbc.queryForObject(
                "SELECT id FROM workplaces WHERE business_registration_number = ?",
                Long.class, businessNumber);
    }

    private Long insertWorker(JdbcTemplate jdbc, String loginId) {
        jdbc.update(
                "INSERT INTO users (login_id, email, password_hash, name, role)"
                        + " VALUES (?, ?, ?, '이알바', 'WORKER')",
                loginId,
                loginId + "@example.com",
                "$2a$10$0000000000000000000000000000000000000000000000000000");
        return jdbc.queryForObject("SELECT id FROM users WHERE login_id = ?", Long.class, loginId);
    }

    /** ck_work_cases_matched_worker가 DRAFT·CANCELED가 아닌 상태에 worker_id NOT NULL을 요구합니다. */
    private Long insertMatchedWorkCase(
            JdbcTemplate jdbc,
            Long employerId,
            Long workplaceId,
            Long workerId,
            String status) {
        jdbc.update(
                "INSERT INTO work_cases"
                        + " (employer_id, worker_id, workplace_id, title, starts_at, ends_at,"
                        + " break_minutes, break_paid, workplace_name, workplace_address,"
                        + " allowed_radius_meters, agreed_wage, terms_version, status)"
                        + " VALUES (?, ?, ?, '주말 홀 서빙 상세 검증', ?, ?, 60, 0, '강남점',"
                        + " '서울 강남구 테헤란로 1 2층', 100.00, 120000, 1, ?)",
                employerId, workerId, workplaceId,
                LocalDateTime.of(2026, 8, 20, 9, 0), LocalDateTime.of(2026, 8, 20, 18, 0),
                status);
        return jdbc.queryForObject(
                "SELECT id FROM work_cases WHERE employer_id = ? AND worker_id = ? AND status = ?"
                        + " ORDER BY id DESC LIMIT 1",
                Long.class, employerId, workerId, status);
    }

    private Long insertEmploymentContractDocument(JdbcTemplate jdbc, Long ownerUserId, Long workCaseId) {
        jdbc.update(
                "INSERT INTO documents"
                        + " (created_by_user_id, owner_user_id, work_case_id, document_type, status)"
                        + " VALUES (?, ?, ?, 'EMPLOYMENT_CONTRACT', 'ACTIVE')",
                ownerUserId, ownerUserId, workCaseId);
        return jdbc.queryForObject(
                "SELECT id FROM documents WHERE work_case_id = ? AND document_type = 'EMPLOYMENT_CONTRACT'",
                Long.class, workCaseId);
    }

    private void insertContract(JdbcTemplate jdbc, Long workCaseId, Long employerId, Long workerId) {
        jdbc.update(
                "INSERT INTO work_contracts"
                        + " (work_case_id, employer_id, worker_id, title, starts_at, ends_at,"
                        + " break_minutes, workplace_name, workplace_address, allowed_radius_meters,"
                        + " agreed_wage, source_terms_version, terms_snapshot, accepted_at)"
                        + " VALUES (?, ?, ?, '주말 홀 서빙 상세 검증', ?, ?, 60, '강남점',"
                        + " '서울 강남구 테헤란로 1 2층', 100.00, 120000, 1, JSON_OBJECT(), ?)",
                workCaseId, employerId, workerId,
                LocalDateTime.of(2026, 8, 20, 9, 0), LocalDateTime.of(2026, 8, 20, 18, 0),
                LocalDateTime.of(2026, 8, 10, 4, 0));
    }

    private void insertEscrow(JdbcTemplate jdbc, Long workCaseId, String status) {
        jdbc.update(
                "INSERT INTO escrows"
                        + " (work_case_id, amount, status, held_at, released_at, refunded_at)"
                        + " VALUES (?, 120000, ?,"
                        + " CASE WHEN ? IN ('HELD', 'RELEASED', 'REFUNDED') THEN NOW(6) END,"
                        + " CASE WHEN ? = 'RELEASED' THEN NOW(6) END,"
                        + " CASE WHEN ? = 'REFUNDED' THEN NOW(6) END)",
                workCaseId, status, status, status, status);
    }

    private void insertSettlement(JdbcTemplate jdbc, Long workCaseId, String status) {
        jdbc.update(
                "INSERT INTO settlements"
                        + " (work_case_id, amount, worker_paid_amount, owner_refund_amount,"
                        + " calculation_reason, calculation_version, calculated_at, status,"
                        + " due_at, processing_at, completed_at)"
                        + " VALUES (?, 120000, 120000, 0, 'LEGACY', 'LEGACY', ?, ?, ?, ?, ?)",
                workCaseId,
                LocalDateTime.of(2026, 8, 21, 0, 5),
                status,
                LocalDateTime.of(2026, 8, 21, 0, 0),
                LocalDateTime.of(2026, 8, 21, 0, 1),
                LocalDateTime.of(2026, 8, 21, 0, 5));
    }

    private void insertAttendance(JdbcTemplate jdbc, Long workCaseId, Long workerId, String type) {
        LocalDateTime capturedAt = "CHECK_IN".equals(type)
                ? LocalDateTime.of(2026, 8, 20, 9, 0)
                : LocalDateTime.of(2026, 8, 20, 18, 0);
        jdbc.update(
                "INSERT INTO attendance_records"
                        + " (work_case_id, worker_id, attendance_type, captured_at, attempted_at, result)"
                        + " VALUES (?, ?, ?, ?, ?, 'SUCCESS')",
                workCaseId, workerId, type, capturedAt, capturedAt);
    }

    private void cleanUp(JdbcTemplate jdbc, Long... ownerUserIds) {
        for (Long ownerUserId : ownerUserIds) {
            // work_cases를 FK RESTRICT로 참조하는 자식 테이블을 전부 먼저 지웁니다. 상세 조회
            // 검증이 심은 계약·문서·에스크로·정산·근태 행을 지우지 않으면 work_cases DELETE가
            // 참조 무결성 오류로 실패합니다.
            String childScope = " WHERE work_case_id IN"
                    + " (SELECT id FROM work_cases WHERE employer_id = ?)";
            jdbc.update("DELETE FROM attendance_records" + childScope, ownerUserId);
            jdbc.update("DELETE FROM settlements" + childScope, ownerUserId);
            jdbc.update("DELETE FROM escrows" + childScope, ownerUserId);
            jdbc.update("DELETE FROM documents" + childScope, ownerUserId);
            jdbc.update("DELETE FROM work_contracts" + childScope, ownerUserId);
            jdbc.update("DELETE FROM work_invitations" + childScope, ownerUserId);
            jdbc.update("DELETE FROM work_cases WHERE employer_id = ?", ownerUserId);
            jdbc.update("DELETE FROM workplaces WHERE owner_user_id = ?", ownerUserId);
            jdbc.update("DELETE FROM users WHERE id = ?", ownerUserId);
        }
    }
}
