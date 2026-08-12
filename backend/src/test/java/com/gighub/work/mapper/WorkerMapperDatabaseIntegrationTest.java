package com.gighub.work.mapper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import javax.sql.DataSource;

import com.gighub.config.RootConfig;
import com.gighub.work.domain.WorkCaseStatus;
import com.gighub.work.mapper.param.WorkerWorkCaseListQuery;
import com.gighub.work.mapper.result.WorkerHomeCandidateRow;
import com.gighub.work.mapper.result.WorkerWorkCaseRow;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 실제 MySQL Head Schema에서 WORKER 홈·근무 이력 Mapper의 SQL 계약을 검증합니다.
 *
 * <p>Mapper XML은 컴파일 대상이 아니라 컬럼 오타와 {@code <constructor>} 인자 순서가 정적
 * 검사로 드러나지 않습니다. 이 Mapper는 특히 위험합니다.
 * {@link WorkerHomeCandidateRow}는 {@code LocalDateTime} 필드를 여섯 개, {@code String} 필드를
 * 다섯 개 갖고 있어 인자 순서가 뒤바뀌어도 타입이 맞아 조용히 통과합니다. 그래서 이 Test는
 * 필드마다 서로 다른 값을 넣고 값 자체를 확인합니다.</p>
 *
 * <p>{@code database} Tag의 Opt-in Test이며 {@code ./gradlew databaseTest}로만 실행합니다.</p>
 */
@Tag("database")
class WorkerMapperDatabaseIntegrationTest {

    private static final BigDecimal LATITUDE = new BigDecimal("37.1234567");
    private static final BigDecimal LONGITUDE = new BigDecimal("127.1234567");

    /** 오늘 경계를 실행일에 맡기면 Fixture 시각이 매일 달라져 후보 판정이 흔들립니다. */
    private static final LocalDate TODAY = LocalDate.of(2026, 8, 10);
    private static final LocalDateTime CARRY_OVER_START = TODAY.minusDays(1).atStartOfDay();
    private static final LocalDateTime TODAY_START = TODAY.atStartOfDay();
    private static final LocalDateTime TOMORROW_START = TODAY.plusDays(1).atStartOfDay();

    private static final long DAILY_WAGE = 120_000L;

    private String businessNumberPrefix;

    @Test
    @Timeout(60)
    void keepsWorkerReadModelSqlContractOnCurrentMysqlSchema() {
        try (AnnotationConfigApplicationContext context =
                     new AnnotationConfigApplicationContext(RootConfig.class)) {
            JdbcTemplate jdbc = new JdbcTemplate(context.getBean(DataSource.class));
            WorkerMapper mapper = context.getBean(WorkerMapper.class);

            String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
            // 사업자등록번호는 Unique 숫자 10자리라 실행마다 앞자리를 다르게 만듭니다.
            businessNumberPrefix = String.format(
                    "%06d", ThreadLocalRandom.current().nextInt(1_000_000));

            Long ownerUserId = insertUser(jdbc, "qa165o" + suffix, "김사장", "OWNER");
            Long workerUserId = insertUser(jdbc, "qa165a" + suffix, "이알바", "WORKER");
            Long otherWorkerUserId = insertUser(jdbc, "qa165b" + suffix, "박알바", "WORKER");

            try {
                Long workplaceId = insertWorkplace(jdbc, ownerUserId);

                verifyRowMappingKeepsConstructorOrder(
                        jdbc, mapper, ownerUserId, workplaceId, otherWorkerUserId);
                verifyTodayCandidatePicksCarriedOverInProgress(
                        jdbc, mapper, ownerUserId, workplaceId, workerUserId);
                verifyHistoryExcludesDraftAndCanceled(
                        jdbc, mapper, ownerUserId, workplaceId, otherWorkerUserId);
                verifyHistoryIsWorkerScopedAndOrdered(
                        jdbc, mapper, ownerUserId, workplaceId, workerUserId, otherWorkerUserId);
            } finally {
                cleanUp(jdbc, ownerUserId, workerUserId, otherWorkerUserId);
            }
        }
    }

    /**
     * 같은 타입끼리 인자가 뒤바뀌어도 조용히 통과하므로 필드마다 다른 값을 읽어 확인합니다.
     *
     * <p>특히 {@code checkedInAt}(captured_at), {@code checkInAttemptedAt}(attempted_at),
     * {@code checkedOutAt}, {@code settlementDueAt} 네 시각과
     * {@code title}/{@code workplaceName}, {@code escrowStatus}/{@code settlementStatus} 두 쌍이
     * 서로 바뀌면 화면이 조용히 다른 값을 보여줍니다.</p>
     */
    private void verifyRowMappingKeepsConstructorOrder(
            JdbcTemplate jdbc,
            WorkerMapper mapper,
            Long ownerUserId,
            Long workplaceId,
            Long workerUserId) {
        LocalDateTime startsAt = TODAY.atTime(9, 0);
        LocalDateTime endsAt = TODAY.atTime(18, 0);
        LocalDateTime attemptedAt = TODAY.atTime(9, 5);
        LocalDateTime capturedAt = TODAY.atTime(9, 7);
        LocalDateTime checkedOutAt = TODAY.atTime(18, 2);
        LocalDateTime settlementDueAt = TODAY.plusDays(2).atStartOfDay();

        Long workCaseId = insertWorkCase(
                jdbc, ownerUserId, workplaceId, workerUserId,
                "홀 서빙 마감", startsAt, endsAt, "COMPLETED");
        insertAttendance(jdbc, workCaseId, workerUserId, "CHECK_IN", capturedAt, attemptedAt, "SUCCESS");
        insertAttendance(
                jdbc, workCaseId, workerUserId, "CHECK_OUT", checkedOutAt, checkedOutAt, "SUCCESS");
        insertEscrow(jdbc, workCaseId, "HELD");
        insertSettlement(jdbc, workCaseId, "SCHEDULED", settlementDueAt);

        WorkerHomeCandidateRow row = mapper.findTodayCandidate(
                workerUserId, CARRY_OVER_START, TODAY_START, TOMORROW_START);

        assertNotNull(row);
        assertEquals(workCaseId, row.getWorkCaseId());
        // 아래 두 문자열은 타입이 같아 순서가 어긋나면 조용히 뒤바뀝니다.
        assertEquals("홀 서빙 마감", row.getTitle());
        assertEquals("강남점", row.getWorkplaceName());
        assertEquals(startsAt, row.getStartsAt());
        assertEquals(endsAt, row.getEndsAt());
        assertEquals(60, row.getBreakMinutes());
        assertEquals(Boolean.FALSE, row.getBreakPaid());
        assertEquals(DAILY_WAGE, row.getDailyWage());
        assertEquals(WorkCaseStatus.COMPLETED, row.getStatus());
        // 성공 근태만 읽어야 합니다. captured_at과 attempted_at은 서로 다른 값입니다.
        assertEquals(capturedAt, row.getCheckedInAt());
        assertEquals(attemptedAt, row.getCheckInAttemptedAt());
        assertEquals(checkedOutAt, row.getCheckedOutAt());
        assertEquals("HELD", row.getEscrowStatus());
        assertEquals("SCHEDULED", row.getSettlementStatus());
        assertEquals(settlementDueAt, row.getSettlementDueAt());

        // 실패 근태가 성공 시각을 덮어쓰면 안 됩니다. uk_attendance_records_success는
        // REJECTED 행을 여러 건 허용하므로 JOIN 조건이 result를 걸지 않으면 여기서 드러납니다.
        insertAttendance(
                jdbc, workCaseId, workerUserId, "CHECK_IN",
                TODAY.atTime(10, 30), TODAY.atTime(10, 30), "REJECTED");
        WorkerHomeCandidateRow afterRejected = mapper.findTodayCandidate(
                workerUserId, CARRY_OVER_START, TODAY_START, TOMORROW_START);
        assertEquals(capturedAt, afterRejected.getCheckedInAt(), "성공 CHECK_IN만 읽어야 합니다.");
    }

    /**
     * 후보 선택은 상태 우선순위와 이월 하한 두 규칙이 함께 걸립니다.
     *
     * <p>전날 밤에 시작해 아직 끝나지 않은 근무가 오늘 시작하는 근무를 이겨야 하고, 이월
     * 하한보다 이전에 멈춘 {@code IN_PROGRESS}는 후보가 되면 안 됩니다. 하한이 없으면 몇 달 전
     * 멈춘 근무가 계속 오늘 근무로 올라옵니다.</p>
     */
    private void verifyTodayCandidatePicksCarriedOverInProgress(
            JdbcTemplate jdbc,
            WorkerMapper mapper,
            Long ownerUserId,
            Long workplaceId,
            Long workerUserId) {
        Long staleId = insertWorkCase(
                jdbc, ownerUserId, workplaceId, workerUserId, "이월 하한 밖 근무",
                TODAY.minusDays(2).atTime(22, 0), TODAY.minusDays(1).atTime(6, 0), "IN_PROGRESS");
        Long carriedOverId = insertWorkCase(
                jdbc, ownerUserId, workplaceId, workerUserId, "전날 야간 근무",
                TODAY.minusDays(1).atTime(22, 0), TODAY.atTime(6, 0), "IN_PROGRESS");
        Long todayReadyId = insertWorkCase(
                jdbc, ownerUserId, workplaceId, workerUserId, "오늘 주간 근무",
                TODAY.atTime(9, 0), TODAY.atTime(18, 0), "READY");

        WorkerHomeCandidateRow row = mapper.findTodayCandidate(
                workerUserId, CARRY_OVER_START, TODAY_START, TOMORROW_START);

        assertNotNull(row);
        assertEquals(carriedOverId, row.getWorkCaseId(), "IN_PROGRESS가 READY보다 앞서야 합니다.");

        // 이월 후보를 지우면 오늘 시작하는 READY가 올라오고, 하한 밖 IN_PROGRESS는 끝내 뽑히지
        // 않아야 합니다. 하한이 빠져 있으면 여기서 staleId가 돌아옵니다.
        jdbc.update("DELETE FROM work_cases WHERE id = ?", carriedOverId);
        WorkerHomeCandidateRow afterCarryOverGone = mapper.findTodayCandidate(
                workerUserId, CARRY_OVER_START, TODAY_START, TOMORROW_START);
        assertEquals(todayReadyId, afterCarryOverGone.getWorkCaseId());

        jdbc.update("DELETE FROM work_cases WHERE id = ?", todayReadyId);
        assertNull(
                mapper.findTodayCandidate(workerUserId, CARRY_OVER_START, TODAY_START, TOMORROW_START),
                "이월 하한 밖 IN_PROGRESS는 오늘 근무가 아닙니다.");

        // 뒤 검증이 쓰도록 이월 하한 밖 근무 한 건만 남깁니다.
        assertNotNull(staleId);
    }

    /**
     * 이력은 배정이 확정된 이후 상태만 셉니다.
     *
     * <p>{@code CANCELED}는 배정 뒤 취소돼도 {@code worker_id}가 남으므로 {@code worker_id}
     * 조건만으로는 걸러지지 않습니다. 목록과 건수가 같은 조건을 봐야 {@code totalElements}와
     * 실제 {@code content}가 어긋나지 않습니다.</p>
     */
    private void verifyHistoryExcludesDraftAndCanceled(
            JdbcTemplate jdbc,
            WorkerMapper mapper,
            Long ownerUserId,
            Long workplaceId,
            Long workerUserId) {
        insertWorkCase(
                jdbc, ownerUserId, workplaceId, workerUserId, "취소된 근무",
                TODAY.minusDays(3).atTime(9, 0), TODAY.minusDays(3).atTime(18, 0), "CANCELED");
        insertWorkCase(
                jdbc, ownerUserId, workplaceId, workerUserId, "작성 중 근무",
                TODAY.minusDays(4).atTime(9, 0), TODAY.minusDays(4).atTime(18, 0), "DRAFT");

        WorkerWorkCaseListQuery query = query(workerUserId);
        List<WorkerWorkCaseRow> rows = mapper.findPage(query);

        assertEquals(1, rows.size(), "확정 이후 근무 한 건만 남아야 합니다.");
        assertEquals(WorkCaseStatus.COMPLETED, rows.get(0).getStatus());
        assertEquals(1L, mapper.countByWorker(query), "건수도 같은 조건을 봐야 합니다.");
    }

    /**
     * 남의 근무가 새면 안 되고 정렬은 {@code starts_at DESC}로 고정입니다.
     *
     * <p>Page 크기를 좁혀도 건수는 전체를 세야 합니다. 둘이 어긋나면 Frontend가 마지막 Page를
     * 잘못 계산합니다.</p>
     */
    private void verifyHistoryIsWorkerScopedAndOrdered(
            JdbcTemplate jdbc,
            WorkerMapper mapper,
            Long ownerUserId,
            Long workplaceId,
            Long workerUserId,
            Long otherWorkerUserId) {
        // 이 시점 workerUserId에게는 이월 하한 밖 IN_PROGRESS 한 건만 남아 있습니다.
        Long newestId = insertWorkCase(
                jdbc, ownerUserId, workplaceId, workerUserId, "가장 최근 근무",
                TODAY.minusDays(1).atTime(9, 0), TODAY.minusDays(1).atTime(18, 0), "COMPLETED");
        Long oldestId = insertWorkCase(
                jdbc, ownerUserId, workplaceId, workerUserId, "가장 오래된 근무",
                TODAY.minusDays(9).atTime(9, 0), TODAY.minusDays(9).atTime(18, 0), "NO_SHOW");

        List<WorkerWorkCaseRow> rows = mapper.findPage(query(workerUserId));

        assertEquals(3, rows.size(), "이월 하한 밖 IN_PROGRESS도 이력에는 남습니다.");
        assertEquals(newestId, rows.get(0).getWorkCaseId(), "최근 근무가 먼저 와야 합니다.");
        assertEquals("이월 하한 밖 근무", rows.get(1).getTitle());
        assertEquals(oldestId, rows.get(2).getWorkCaseId());
        assertEquals(3L, mapper.countByWorker(query(workerUserId)));

        WorkerWorkCaseListQuery firstPage = WorkerWorkCaseListQuery.builder()
                .workerId(workerUserId)
                .size(2)
                .offset(0)
                .build();
        assertEquals(2, mapper.findPage(firstPage).size());
        assertEquals(3L, mapper.countByWorker(firstPage), "건수는 Page 크기와 무관해야 합니다.");

        WorkerWorkCaseListQuery secondPage = WorkerWorkCaseListQuery.builder()
                .workerId(workerUserId)
                .size(2)
                .offset(2)
                .build();
        List<WorkerWorkCaseRow> secondPageRows = mapper.findPage(secondPage);
        assertEquals(1, secondPageRows.size());
        assertEquals(oldestId, secondPageRows.get(0).getWorkCaseId(), "Page 2가 이어져야 합니다.");

        List<WorkerWorkCaseRow> otherRows = mapper.findPage(query(otherWorkerUserId));
        assertTrue(
                otherRows.stream().noneMatch(row -> row.getTitle().equals("가장 최근 근무")),
                "다른 WORKER의 근무가 보이면 안 됩니다.");

        // 홈 후보도 당사자 조건이 SQL 안에 있어야 합니다.
        assertNull(
                mapper.findTodayCandidate(
                        otherWorkerUserId + 100_000L, CARRY_OVER_START, TODAY_START, TOMORROW_START),
                "존재하지 않는 WORKER에게는 후보가 없어야 합니다.");
    }

    private WorkerWorkCaseListQuery query(Long workerId) {
        return WorkerWorkCaseListQuery.builder()
                .workerId(workerId)
                .size(20)
                .offset(0)
                .build();
    }

    private Long insertWorkCase(
            JdbcTemplate jdbc,
            Long ownerUserId,
            Long workplaceId,
            Long workerUserId,
            String title,
            LocalDateTime startsAt,
            LocalDateTime endsAt,
            String status) {
        jdbc.update(
                "INSERT INTO work_cases"
                        + " (employer_id, worker_id, workplace_id, title, starts_at, ends_at,"
                        + " break_minutes, break_paid, workplace_name, workplace_address,"
                        + " workplace_latitude, workplace_longitude, allowed_radius_meters,"
                        + " agreed_wage, terms_version, status, canceled_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, 60, 0, '강남점', '서울 강남구 테헤란로 1 2층',"
                        + " ?, ?, 100.00, ?, 1, ?,"
                        + " CASE WHEN ? = 'CANCELED' THEN CURRENT_TIMESTAMP(6) ELSE NULL END)",
                ownerUserId, workerUserId, workplaceId, title, startsAt, endsAt,
                LATITUDE, LONGITUDE, DAILY_WAGE, status, status);
        return jdbc.queryForObject(
                "SELECT id FROM work_cases WHERE workplace_id = ? AND title = ? AND starts_at = ?",
                Long.class, workplaceId, title, startsAt);
    }

    private void insertAttendance(
            JdbcTemplate jdbc,
            Long workCaseId,
            Long workerUserId,
            String attendanceType,
            LocalDateTime capturedAt,
            LocalDateTime attemptedAt,
            String result) {
        jdbc.update(
                "INSERT INTO attendance_records"
                        + " (work_case_id, worker_id, attendance_type, captured_at, attempted_at,"
                        + " result) VALUES (?, ?, ?, ?, ?, ?)",
                workCaseId, workerUserId, attendanceType, capturedAt, attemptedAt, result);
    }

    /** {@code fk_escrows_case_wage} 때문에 금액이 {@code agreed_wage}와 같아야 합니다. */
    private void insertEscrow(JdbcTemplate jdbc, Long workCaseId, String status) {
        jdbc.update(
                "INSERT INTO escrows"
                        + " (work_case_id, amount, status, held_at, released_at, refunded_at)"
                        + " VALUES (?, ?, ?,"
                        + " CASE WHEN ? IN ('HELD', 'RELEASED', 'REFUNDED') THEN NOW(6) END,"
                        + " CASE WHEN ? = 'RELEASED' THEN NOW(6) END,"
                        + " CASE WHEN ? = 'REFUNDED' THEN NOW(6) END)",
                workCaseId, DAILY_WAGE, status, status, status, status);
    }

    /** {@code fk_settlements_case_wage} 때문에 금액이 {@code agreed_wage}와 같아야 합니다. */
    private void insertSettlement(
            JdbcTemplate jdbc, Long workCaseId, String status, LocalDateTime dueAt) {
        jdbc.update(
                "INSERT INTO settlements (work_case_id, amount, status, due_at)"
                        + " VALUES (?, ?, ?, ?)",
                workCaseId, DAILY_WAGE, status, dueAt);
    }

    private Long insertUser(JdbcTemplate jdbc, String loginId, String name, String role) {
        jdbc.update(
                "INSERT INTO users (login_id, email, password_hash, name, role)"
                        + " VALUES (?, ?, ?, ?, ?)",
                loginId,
                loginId + "@example.com",
                "$2a$10$0000000000000000000000000000000000000000000000000000",
                name,
                role);
        return jdbc.queryForObject("SELECT id FROM users WHERE login_id = ?", Long.class, loginId);
    }

    private Long insertWorkplace(JdbcTemplate jdbc, Long ownerUserId) {
        String businessNumber = businessNumberPrefix + "0001";
        jdbc.update(
                "INSERT INTO workplaces (owner_user_id, business_registration_number, name,"
                        + " representative_name, road_address, detail_address, phone,"
                        + " latitude, longitude, radius_meters, status)"
                        + " VALUES (?, ?, '강남점', '김사장', '서울 강남구 테헤란로 1', '2층',"
                        + " '0212345678', ?, ?, 100.00, 'ACTIVE')",
                ownerUserId, businessNumber, LATITUDE, LONGITUDE);
        return jdbc.queryForObject(
                "SELECT id FROM workplaces WHERE business_registration_number = ?",
                Long.class, businessNumber);
    }

    /** escrows·settlements·attendance_records가 work_cases를 RESTRICT로 잡으므로 먼저 지웁니다. */
    private void cleanUp(JdbcTemplate jdbc, Long ownerUserId, Long... workerUserIds) {
        String ownedCases = "(SELECT id FROM work_cases WHERE employer_id = ?)";
        jdbc.update("DELETE FROM attendance_records WHERE work_case_id IN " + ownedCases, ownerUserId);
        jdbc.update("DELETE FROM settlements WHERE work_case_id IN " + ownedCases, ownerUserId);
        jdbc.update("DELETE FROM escrows WHERE work_case_id IN " + ownedCases, ownerUserId);
        jdbc.update("DELETE FROM work_cases WHERE employer_id = ?", ownerUserId);
        jdbc.update("DELETE FROM workplaces WHERE owner_user_id = ?", ownerUserId);
        for (Long workerUserId : workerUserIds) {
            jdbc.update("DELETE FROM users WHERE id = ?", workerUserId);
        }
        jdbc.update("DELETE FROM users WHERE id = ?", ownerUserId);
    }
}
