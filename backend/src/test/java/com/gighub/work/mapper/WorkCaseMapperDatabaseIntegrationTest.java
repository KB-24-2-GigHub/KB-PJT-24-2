package com.gighub.work.mapper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

import javax.sql.DataSource;

import com.gighub.config.RootConfig;
import com.gighub.invitation.mapper.InvitationMapper;
import com.gighub.work.domain.WorkCaseStatus;
import com.gighub.work.mapper.param.WorkCaseInsertParam;
import com.gighub.work.mapper.param.WorkCaseListQuery;
import com.gighub.work.mapper.param.WorkCaseTermsUpdateParam;
import com.gighub.work.mapper.result.OwnedWorkplaceSnapshotRow;
import com.gighub.work.mapper.result.SettlementSummaryRow;
import com.gighub.work.mapper.result.WorkCaseListRow;
import com.gighub.work.mapper.result.WorkCaseLockRow;
import com.gighub.work.mapper.result.WorkCaseStatusCountRow;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 실제 MySQL Head Schema에서 근무 Case Mapper의 SQL 계약을 검증합니다.
 *
 * <p>Mapper XML은 컴파일 대상이 아니라 문법 오류·컬럼 오타·{@code <constructor>} 인자 순서가
 * 정적 검사로 드러나지 않습니다. 특히 인자 순서는 같은 타입끼리 조용히 뒤바뀌므로 실제 값을
 * 읽어 확인해야 합니다.</p>
 *
 * <p>{@code database} Tag의 Opt-in Test이며 {@code ./gradlew databaseTest}로만 실행합니다.</p>
 */
@Tag("database")
class WorkCaseMapperDatabaseIntegrationTest {

    private static final BigDecimal LATITUDE = new BigDecimal("37.1234567");
    private static final BigDecimal LONGITUDE = new BigDecimal("127.1234567");
    private static final BigDecimal RADIUS_METERS = new BigDecimal("100.00");
    private static final LocalDateTime STARTS_AT = LocalDateTime.of(2026, 8, 10, 9, 0);
    private static final LocalDateTime ENDS_AT = LocalDateTime.of(2026, 8, 10, 18, 0);

    private String businessNumberPrefix;

    @Test
    @Timeout(60)
    void keepsWorkCaseSqlContractOnCurrentMysqlSchema() {
        try (AnnotationConfigApplicationContext context =
                     new AnnotationConfigApplicationContext(RootConfig.class)) {
            JdbcTemplate jdbc = new JdbcTemplate(context.getBean(DataSource.class));
            WorkCaseMapper mapper = context.getBean(WorkCaseMapper.class);
            InvitationMapper invitationMapper = context.getBean(InvitationMapper.class);

            String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
            // 사업자등록번호는 Unique 숫자 10자리라 실행마다 앞자리를 다르게 만듭니다.
            // 고정값을 쓰면 이전 실행이 정리 전에 중단됐을 때 다음 실행이 중복으로 실패합니다.
            businessNumberPrefix = String.format(
                    "%06d", ThreadLocalRandom.current().nextInt(1_000_000));

            Long ownerUserId = insertOwner(jdbc, "qa154a" + suffix);
            Long otherOwnerUserId = insertOwner(jdbc, "qa154b" + suffix);

            try {
                Long activeWorkplaceId = insertWorkplace(jdbc, ownerUserId, 1, "ACTIVE");
                Long inactiveWorkplaceId = insertWorkplace(jdbc, ownerUserId, 2, "INACTIVE");

                OwnedWorkplaceSnapshotRow snapshot = verifySnapshotScope(
                        mapper, activeWorkplaceId, inactiveWorkplaceId, ownerUserId, otherOwnerUserId);
                verifyManageableWorkplaceScope(
                        mapper, activeWorkplaceId, inactiveWorkplaceId, ownerUserId, otherOwnerUserId);

                Long editedCaseId = verifyInsertFixesServerOwnedColumns(
                        jdbc, mapper, ownerUserId, activeWorkplaceId, snapshot);
                verifyLockReadsCurrentState(mapper, editedCaseId, ownerUserId, activeWorkplaceId);
                verifyUpdateBumpsVersionOnlyForDraft(jdbc, mapper, editedCaseId);

                verifyRevokeTouchesOnlyPending(
                        jdbc, mapper, invitationMapper, ownerUserId, activeWorkplaceId, snapshot);
                verifyDeleteAndCancelBranches(
                        jdbc, mapper, ownerUserId, activeWorkplaceId, snapshot);
                verifyCountByStatusIsOwnerScoped(
                        mapper, activeWorkplaceId, ownerUserId, otherOwnerUserId);
                verifyListFiltersSearchesAndOrders(
                        jdbc, mapper, ownerUserId, otherOwnerUserId, activeWorkplaceId, suffix);
            } finally {
                cleanUp(jdbc, ownerUserId, otherOwnerUserId);
            }
        }
    }

    /** 소유권과 ACTIVE 조건이 SQL 안에 있어야 남의 사업장이 Snapshot 원본으로 새지 않습니다. */
    private OwnedWorkplaceSnapshotRow verifySnapshotScope(
            WorkCaseMapper mapper,
            Long activeWorkplaceId,
            Long inactiveWorkplaceId,
            Long ownerUserId,
            Long otherOwnerUserId) {
        OwnedWorkplaceSnapshotRow snapshot =
                mapper.findOwnedActiveWorkplace(activeWorkplaceId, ownerUserId);

        assertNotNull(snapshot);
        assertEquals(activeWorkplaceId, snapshot.getWorkplaceId());
        // 아래 세 문자열은 타입이 같아 <constructor> 순서가 어긋나면 조용히 뒤바뀝니다.
        assertEquals("강남점", snapshot.getWorkplaceName());
        assertEquals("서울 강남구 테헤란로 1", snapshot.getRoadAddress());
        assertEquals("2층", snapshot.getDetailAddress());
        assertEquals(0, LATITUDE.compareTo(snapshot.getLatitude()));
        assertEquals(0, LONGITUDE.compareTo(snapshot.getLongitude()));
        assertEquals(0, RADIUS_METERS.compareTo(snapshot.getRadiusMeters()));

        assertNull(mapper.findOwnedActiveWorkplace(activeWorkplaceId, otherOwnerUserId));
        assertNull(mapper.findOwnedActiveWorkplace(inactiveWorkplaceId, ownerUserId));
        return snapshot;
    }

    /**
     * Summary·목록은 ACTIVE와 INACTIVE 둘 다 관리 대상이라는 점에서
     * {@link #verifySnapshotScope}가 확인하는 등록 대상 범위와 다릅니다.
     */
    private void verifyManageableWorkplaceScope(
            WorkCaseMapper mapper,
            Long activeWorkplaceId,
            Long inactiveWorkplaceId,
            Long ownerUserId,
            Long otherOwnerUserId) {
        assertTrue(mapper.existsOwnedManageableWorkplace(activeWorkplaceId, ownerUserId));
        assertTrue(mapper.existsOwnedManageableWorkplace(inactiveWorkplaceId, ownerUserId));
        assertFalse(mapper.existsOwnedManageableWorkplace(activeWorkplaceId, otherOwnerUserId));
        assertFalse(mapper.existsOwnedManageableWorkplace(-1L, ownerUserId));
    }

    /** worker_id, terms_version, status와 저장 시각은 호출자가 정할 수 없는 계약값입니다. */
    private Long verifyInsertFixesServerOwnedColumns(
            JdbcTemplate jdbc,
            WorkCaseMapper mapper,
            Long ownerUserId,
            Long workplaceId,
            OwnedWorkplaceSnapshotRow snapshot) {
        WorkCaseInsertParam param = insertParam(ownerUserId, workplaceId, snapshot).build();

        assertEquals(1, mapper.insert(param));
        assertNotNull(param.getWorkCaseId(), "useGeneratedKeys가 생성 Key를 채워야 합니다.");

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT worker_id, terms_version, status, break_paid, workplace_address,"
                        + " allowed_radius_meters, agreed_wage, starts_at, ends_at"
                        + " FROM work_cases WHERE id = ?",
                param.getWorkCaseId());

        assertNull(row.get("worker_id"));
        assertEquals(1, ((Number) row.get("terms_version")).intValue());
        assertEquals("DRAFT", row.get("status"));
        assertEquals(0, ((Number) row.get("break_paid")).intValue());
        assertEquals("서울 강남구 테헤란로 1 2층", row.get("workplace_address"));
        assertEquals(0, RADIUS_METERS.compareTo((BigDecimal) row.get("allowed_radius_meters")));
        assertEquals(120_000L, ((Number) row.get("agreed_wage")).longValue());

        // 저장 시각이 시간대 변환 없이 그대로 들어가야 합니다. 변환하면 9시간이 밀립니다.
        // Connector/J가 DATETIME을 시간대 없는 LocalDateTime으로 그대로 돌려주므로 변환이 없습니다.
        assertEquals(STARTS_AT, row.get("starts_at"));
        assertEquals(ENDS_AT, row.get("ends_at"));
        return param.getWorkCaseId();
    }

    /** status가 VARCHAR에서 enum으로 매핑되는지 확인합니다. 매핑이 없으면 여기서 터집니다. */
    private void verifyLockReadsCurrentState(
            WorkCaseMapper mapper,
            Long workCaseId,
            Long ownerUserId,
            Long workplaceId) {
        WorkCaseLockRow lock = mapper.lockById(workCaseId);

        assertNotNull(lock);
        assertEquals(workCaseId, lock.getWorkCaseId());
        assertEquals(ownerUserId, lock.getEmployerId());
        assertEquals(workplaceId, lock.getWorkplaceId());
        assertNull(lock.getWorkerId());
        assertEquals(WorkCaseStatus.DRAFT, lock.getStatus());
        assertEquals(1, lock.getTermsVersion());

        assertNull(mapper.lockById(-1L));
    }

    /** 조건 수정은 DRAFT에서만 성립하고 Version을 정확히 1 올려야 합니다. */
    private void verifyUpdateBumpsVersionOnlyForDraft(
            JdbcTemplate jdbc,
            WorkCaseMapper mapper,
            Long workCaseId) {
        assertEquals(1, mapper.updateDraftTerms(updateParam(workCaseId).build()));
        assertEquals(2, currentTermsVersion(jdbc, workCaseId));

        jdbc.update(
                "UPDATE work_cases SET status = 'CANCELED', canceled_at = CURRENT_TIMESTAMP(6)"
                        + " WHERE id = ?",
                workCaseId);

        assertEquals(0, mapper.updateDraftTerms(updateParam(workCaseId).build()));
        assertEquals(2, currentTermsVersion(jdbc, workCaseId), "DRAFT가 아니면 Version이 그대로여야 합니다.");
    }

    /** 조건 변경은 PENDING 초대만 철회하고 종료된 초대 이력은 보존해야 합니다. */
    private void verifyRevokeTouchesOnlyPending(
            JdbcTemplate jdbc,
            WorkCaseMapper mapper,
            InvitationMapper invitationMapper,
            Long ownerUserId,
            Long workplaceId,
            OwnedWorkplaceSnapshotRow snapshot) {
        Long workCaseId = insertDraft(mapper, ownerUserId, workplaceId, snapshot);
        insertInvitation(jdbc, workCaseId, "PENDING", "p");
        insertInvitation(jdbc, workCaseId, "EXPIRED", "e");

        assertEquals(1, invitationMapper.revokePendingByWorkCaseIdNow(workCaseId));

        assertEquals(1, countInvitations(jdbc, workCaseId, "REVOKED"));
        assertEquals(1, countInvitations(jdbc, workCaseId, "EXPIRED"));
        assertEquals(0, countInvitations(jdbc, workCaseId, "PENDING"));
        assertEquals(2, mapper.countInvitations(workCaseId), "이력은 상태와 무관하게 남아야 합니다.");

        assertEquals(0, invitationMapper.revokePendingByWorkCaseIdNow(workCaseId));
    }

    /** 초대 이력 유무가 물리 삭제와 취소 전이를 가릅니다. */
    private void verifyDeleteAndCancelBranches(
            JdbcTemplate jdbc,
            WorkCaseMapper mapper,
            Long ownerUserId,
            Long workplaceId,
            OwnedWorkplaceSnapshotRow snapshot) {
        Long deletableId = insertDraft(mapper, ownerUserId, workplaceId, snapshot);
        assertEquals(0, mapper.countInvitations(deletableId));
        assertEquals(1, mapper.deleteDraft(deletableId));
        assertEquals(0, mapper.deleteDraft(deletableId), "이미 지운 행은 0이어야 합니다.");

        Long cancelableId = insertDraft(mapper, ownerUserId, workplaceId, snapshot);
        insertInvitation(jdbc, cancelableId, "PENDING", "c");
        assertTrue(mapper.countInvitations(cancelableId) > 0);

        assertEquals(1, mapper.cancelDraft(cancelableId));
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT status, canceled_at FROM work_cases WHERE id = ?", cancelableId);
        assertEquals("CANCELED", row.get("status"));
        assertNotNull(row.get("canceled_at"));

        assertEquals(0, mapper.cancelDraft(cancelableId), "이미 취소된 행은 0이어야 합니다.");
    }

    /** 집계는 enum으로 돌아와야 하고 다른 OWNER에게는 한 건도 보이지 않아야 합니다. */
    private void verifyCountByStatusIsOwnerScoped(
            WorkCaseMapper mapper,
            Long workplaceId,
            Long ownerUserId,
            Long otherOwnerUserId) {
        Map<WorkCaseStatus, Long> byStatus = mapper.countByStatus(workplaceId, ownerUserId).stream()
                .collect(Collectors.toMap(
                        WorkCaseStatusCountRow::getStatus,
                        WorkCaseStatusCountRow::getCaseCount));

        assertEquals(2, byStatus.size(), "남은 상태는 DRAFT와 CANCELED 둘뿐이어야 합니다.");
        assertEquals(1L, byStatus.get(WorkCaseStatus.DRAFT));
        assertEquals(2L, byStatus.get(WorkCaseStatus.CANCELED));

        List<WorkCaseStatusCountRow> otherOwnerCounts =
                mapper.countByStatus(workplaceId, otherOwnerUserId);
        assertTrue(otherOwnerCounts.isEmpty(), "다른 OWNER에게는 건수가 보이면 안 됩니다.");
    }

    /**
     * 목록 조회는 이 시점까지의 시나리오가 남긴 기존 근무(제목 "주말 홀 서빙" 다수 포함)와 같은
     * 사업장을 공유하므로, 검색어로는 이번 실행에서만 유일한 표식을 씁니다. 고정 문자열을 쓰면
     * 기존 행이 우연히 검색어에 걸려 건수 assertion이 실행마다 달라집니다.
     */
    private void verifyListFiltersSearchesAndOrders(
            JdbcTemplate jdbc,
            WorkCaseMapper mapper,
            Long ownerUserId,
            Long otherOwnerUserId,
            Long workplaceId,
            String suffix) {
        String marker = "IT" + suffix;
        Long workerUserId = insertWorker(jdbc, "qa154c" + suffix);

        verifySettlementSnapshotMapping(
                jdbc, mapper, ownerUserId, workplaceId, workerUserId, marker);

        Long olderId = insertListFixture(
                jdbc, ownerUserId, workplaceId, marker + " 저녁 근무",
                LocalDateTime.of(2026, 8, 9, 9, 0), "DRAFT", null);
        Long matchedId = insertListFixture(
                jdbc, ownerUserId, workplaceId, "평일 주방 보조",
                LocalDateTime.of(2026, 8, 10, 9, 0), "READY", workerUserId);
        Long newerId = insertListFixture(
                jdbc, ownerUserId, workplaceId, marker + " 주말 근무",
                LocalDateTime.of(2026, 8, 11, 9, 0), "DRAFT", null);

        try {
            // keyword: 제목 부분 일치. marker가 붙은 두 행(older·newer)만 걸립니다.
            // worker 이름에는 아직 marker가 없으므로 matchedId는 걸리지 않습니다.
            List<WorkCaseListRow> byTitle = findAll(mapper, listQuery(workplaceId, ownerUserId)
                    .keyword(marker)
                    .build());
            assertEquals(2, byTitle.size());

            // 정렬: starts_at DESC, id DESC 고정. marker 필터로 older·newer 두 행만 좁혀서
            // 순서를 확인합니다. worker 이름 갱신 전에 확인해야 matchedId가 섞이지 않습니다.
            List<WorkCaseListRow> ordered = byTitle;
            assertEquals(newerId, ordered.get(0).getWorkCaseId(), "최신 근무가 먼저 와야 합니다.");
            assertEquals(olderId, ordered.get(1).getWorkCaseId());

            // Page 크기: marker 두 건 중 한 건만 요청하면 정확히 한 건만 돌아와야 합니다.
            WorkCaseListQuery firstPage = listQuery(workplaceId, ownerUserId)
                    .keyword(marker)
                    .size(1)
                    .offset(0)
                    .build();
            assertEquals(1, mapper.findPageByFilters(firstPage).size());
            assertEquals(2, mapper.countByFilters(firstPage), "건수는 Page 크기와 무관하게 전체를 세야 합니다.");

            // keyword: worker 이름 부분 일치. 근무 자체 제목에는 없는 단어라 JOIN이 아니면 안 잡힙니다.
            // 이 갱신 이후로는 matchedId도 marker 검색에 걸리므로 marker 관련 검증은 이 앞에서 끝냅니다.
            jdbc.update("UPDATE users SET name = ? WHERE id = ?", "이알바" + marker, workerUserId);
            List<WorkCaseListRow> byWorkerName = findAll(mapper, listQuery(workplaceId, ownerUserId)
                    .keyword("이알바" + marker)
                    .build());
            assertEquals(1, byWorkerName.size());
            assertEquals(matchedId, byWorkerName.get(0).getWorkCaseId());
            assertEquals(workerUserId, byWorkerName.get(0).getWorkerId());

            // status 필터
            List<WorkCaseListRow> readyOnly = findAll(mapper, listQuery(workplaceId, ownerUserId)
                    .keyword(null)
                    .status(WorkCaseStatus.READY)
                    .build());
            assertTrue(readyOnly.stream().allMatch(row -> row.getStatus() == WorkCaseStatus.READY));

            // from/to: workDate 경계(포함). 08-09는 제외되고 08-10·08-11만 남습니다.
            WorkCaseListQuery dateRange = listQuery(workplaceId, ownerUserId)
                    .from(LocalDate.of(2026, 8, 10))
                    .to(LocalDate.of(2026, 8, 11))
                    .build();
            List<WorkCaseListRow> inRange = findAll(mapper, dateRange);
            assertTrue(inRange.stream().noneMatch(row -> row.getWorkCaseId().equals(olderId)));
            assertTrue(inRange.stream().anyMatch(row -> row.getWorkCaseId().equals(newerId)));

            // 다른 OWNER에게는 같은 사업장의 근무가 전혀 보이면 안 됩니다.
            WorkCaseListQuery otherOwnerQuery = listQuery(workplaceId, otherOwnerUserId).build();
            assertEquals(0, findAll(mapper, otherOwnerQuery).size());
            assertEquals(0, mapper.countByFilters(otherOwnerQuery));
        } finally {
            jdbc.update(
                    "DELETE FROM work_cases WHERE id IN (?, ?, ?)", olderId, matchedId, newerId);
            jdbc.update("DELETE FROM users WHERE id = ?", workerUserId);
        }
    }

    /** 상세 조회가 금액과 계산 근거를 같은 Settlement Snapshot에서 읽는지 확인합니다. */
    private void verifySettlementSnapshotMapping(
            JdbcTemplate jdbc,
            WorkCaseMapper mapper,
            Long ownerUserId,
            Long workplaceId,
            Long workerUserId,
            String marker) {
        LocalDateTime calculatedAt = LocalDateTime.of(2026, 8, 8, 17, 5);
        Long workCaseId = insertListFixture(
                jdbc,
                ownerUserId,
                workplaceId,
                marker + " 정산 조회",
                LocalDateTime.of(2026, 8, 8, 9, 0),
                "COMPLETED",
                workerUserId);
        try {
            jdbc.update(
                    "INSERT INTO settlements"
                            + " (work_case_id, amount, worker_paid_amount, owner_refund_amount,"
                            + " deduction_base_minutes, late_minutes, early_leave_minutes,"
                            + " calculation_reason, calculation_version, calculated_at, status, due_at)"
                            + " VALUES (?, 100000, 90000, 10000, 420, 42, 0,"
                            + " 'CHECKED_OUT', 'ATTENDANCE_V1', ?, 'SCHEDULED', ?)",
                    workCaseId,
                    calculatedAt,
                    calculatedAt.plusDays(1));

            SettlementSummaryRow row = mapper.findSettlement(workCaseId);

            assertNotNull(row);
            assertEquals("SCHEDULED", row.getStatus());
            assertEquals(100_000L, row.getAmount());
            assertEquals(90_000L, row.getWorkerPaidAmount());
            assertEquals(10_000L, row.getOwnerRefundAmount());
            assertEquals(420L, row.getDeductionBaseMinutes());
            assertEquals(42L, row.getLateMinutes());
            assertEquals(0L, row.getEarlyLeaveMinutes());
            assertEquals("CHECKED_OUT", row.getCalculationReason());
            assertEquals("ATTENDANCE_V1", row.getCalculationVersion());
            assertEquals(calculatedAt, row.getCalculatedAt());
        } finally {
            jdbc.update("DELETE FROM settlements WHERE work_case_id = ?", workCaseId);
            jdbc.update("DELETE FROM work_cases WHERE id = ?", workCaseId);
        }
    }

    private List<WorkCaseListRow> findAll(WorkCaseMapper mapper, WorkCaseListQuery query) {
        return mapper.findPageByFilters(query);
    }

    private WorkCaseListQuery.WorkCaseListQueryBuilder listQuery(Long workplaceId, Long ownerUserId) {
        return WorkCaseListQuery.builder()
                .workplaceId(workplaceId)
                .ownerUserId(ownerUserId)
                .size(20)
                .offset(0);
    }

    /** READY는 매칭 WORKER가 있어야 하는 ck_work_cases_matched_worker 제약을 만족시킵니다. */
    private Long insertListFixture(
            JdbcTemplate jdbc,
            Long ownerUserId,
            Long workplaceId,
            String title,
            LocalDateTime startsAt,
            String status,
            Long workerUserId) {
        jdbc.update(
                "INSERT INTO work_cases"
                        + " (employer_id, worker_id, workplace_id, title, starts_at, ends_at,"
                        + " break_minutes, break_paid, workplace_name, workplace_address,"
                        + " allowed_radius_meters, agreed_wage, terms_version, status)"
                        + " VALUES (?, ?, ?, ?, ?, ?, 60, 0, '강남점', '서울 강남구 테헤란로 1 2층',"
                        + " 100.00, 100000, 1, ?)",
                ownerUserId, workerUserId, workplaceId, title, startsAt, startsAt.plusHours(8), status);
        return jdbc.queryForObject(
                "SELECT id FROM work_cases WHERE workplace_id = ? AND title = ? AND starts_at = ?",
                Long.class, workplaceId, title, startsAt);
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

    private Long insertDraft(
            WorkCaseMapper mapper,
            Long ownerUserId,
            Long workplaceId,
            OwnedWorkplaceSnapshotRow snapshot) {
        WorkCaseInsertParam param = insertParam(ownerUserId, workplaceId, snapshot).build();
        mapper.insert(param);
        return param.getWorkCaseId();
    }

    private WorkCaseInsertParam.WorkCaseInsertParamBuilder insertParam(
            Long ownerUserId,
            Long workplaceId,
            OwnedWorkplaceSnapshotRow snapshot) {
        return WorkCaseInsertParam.builder()
                .employerId(ownerUserId)
                .workplaceId(workplaceId)
                .title("주말 홀 서빙")
                .startsAt(STARTS_AT)
                .endsAt(ENDS_AT)
                .breakMinutes(60)
                .breakPaid(false)
                .workplaceName(snapshot.getWorkplaceName())
                .workplaceAddress(snapshot.getRoadAddress() + " " + snapshot.getDetailAddress())
                .workplaceLatitude(snapshot.getLatitude())
                .workplaceLongitude(snapshot.getLongitude())
                .allowedRadiusMeters(snapshot.getRadiusMeters())
                .dailyWage(120_000L);
    }

    private WorkCaseTermsUpdateParam.WorkCaseTermsUpdateParamBuilder updateParam(Long workCaseId) {
        return WorkCaseTermsUpdateParam.builder()
                .workCaseId(workCaseId)
                .title("주말 홀 서빙(수정)")
                .startsAt(STARTS_AT.plusHours(1))
                .endsAt(ENDS_AT.plusHours(1))
                .breakMinutes(30)
                .breakPaid(true)
                .dailyWage(130_000L);
    }

    private int currentTermsVersion(JdbcTemplate jdbc, Long workCaseId) {
        return jdbc.queryForObject(
                "SELECT terms_version FROM work_cases WHERE id = ?", Integer.class, workCaseId);
    }

    private int countInvitations(JdbcTemplate jdbc, Long workCaseId, String status) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM work_invitations WHERE work_case_id = ? AND status = ?",
                Integer.class, workCaseId, status);
    }

    private void insertInvitation(JdbcTemplate jdbc, Long workCaseId, String status, String nonce) {
        jdbc.update(
                "INSERT INTO work_invitations"
                        + " (work_case_id, token_hash, status, expected_terms_version, expires_at)"
                        + " VALUES (?, UNHEX(SHA2(CONCAT(?, ?), 256)), ?, 1,"
                        + " DATE_ADD(CURRENT_TIMESTAMP(6), INTERVAL 1 DAY))",
                workCaseId, workCaseId, nonce, status);
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

    private Long insertWorkplace(JdbcTemplate jdbc, Long ownerUserId, int index, String status) {
        String businessNumber = businessNumberPrefix + String.format("%04d", index);
        jdbc.update(
                "INSERT INTO workplaces (owner_user_id, business_registration_number, name,"
                        + " representative_name, road_address, detail_address, phone,"
                        + " latitude, longitude, radius_meters, status)"
                        + " VALUES (?, ?, '강남점', '김사장', '서울 강남구 테헤란로 1', '2층',"
                        + " '0212345678', ?, ?, 100.00, ?)",
                ownerUserId, businessNumber, LATITUDE, LONGITUDE, status);
        return jdbc.queryForObject(
                "SELECT id FROM workplaces WHERE business_registration_number = ?",
                Long.class, businessNumber);
    }

    private void cleanUp(JdbcTemplate jdbc, Long... ownerUserIds) {
        for (Long ownerUserId : ownerUserIds) {
            jdbc.update(
                    "DELETE FROM work_invitations WHERE work_case_id IN"
                            + " (SELECT id FROM work_cases WHERE employer_id = ?)",
                    ownerUserId);
            jdbc.update("DELETE FROM work_cases WHERE employer_id = ?", ownerUserId);
            jdbc.update("DELETE FROM workplaces WHERE owner_user_id = ?", ownerUserId);
            jdbc.update("DELETE FROM users WHERE id = ?", ownerUserId);
        }
    }
}
