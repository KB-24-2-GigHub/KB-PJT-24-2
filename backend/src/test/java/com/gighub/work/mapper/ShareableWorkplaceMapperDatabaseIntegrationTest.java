package com.gighub.work.mapper;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import javax.sql.DataSource;

import com.gighub.config.RootConfig;
import com.gighub.work.mapper.param.ShareableWorkplaceListQuery;
import com.gighub.work.mapper.result.ShareableWorkplaceRow;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 실제 MySQL Head Schema에서 보건증 공유 후보 조회 SQL의 계약을 검증합니다.
 *
 * <p>Mapper XML은 컴파일 대상이 아니라 컬럼 오타와 {@code <constructor>} 인자 순서가 정적
 * 검사로 드러나지 않습니다. {@link ShareableWorkplaceRow}는 {@code String} 필드 두 개와
 * {@code LocalDateTime} 필드 두 개를 이웃해 갖고 있어 인자가 뒤바뀌어도 타입이 맞아 조용히
 * 통과합니다. 그래서 필드마다 서로 다른 값을 넣고 값 자체를 확인합니다.</p>
 *
 * <p>후보 상태 목록이 접근 유효성 목록과 다른 것도 여기서 고정합니다. {@code IN_PROGRESS}는
 * 접근 판정에서는 유효하지만 신규 공유 후보는 아닙니다(DEC-DOCUMENT-SHARE-UNIT).</p>
 */
@Tag("database")
class ShareableWorkplaceMapperDatabaseIntegrationTest {

    private static final LocalDateTime EARLIER_START = LocalDateTime.of(2026, 9, 1, 9, 0);
    private static final LocalDateTime EARLIER_END = LocalDateTime.of(2026, 9, 1, 18, 0);
    private static final LocalDateTime LATER_START = LocalDateTime.of(2026, 9, 5, 10, 0);
    private static final LocalDateTime LATER_END = LocalDateTime.of(2026, 9, 5, 19, 0);

    private String businessNumberPrefix;

    @Test
    @Timeout(60)
    void returnsOnlyOwnAcceptedOrReadyRelationshipsOfActiveWorkplaces() {
        try (AnnotationConfigApplicationContext context =
                     new AnnotationConfigApplicationContext(RootConfig.class)) {
            JdbcTemplate jdbc = new JdbcTemplate(context.getBean(DataSource.class));
            WorkerMapper mapper = context.getBean(WorkerMapper.class);

            String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
            businessNumberPrefix = String.format(
                    "%06d", ThreadLocalRandom.current().nextInt(1_000_000));

            long ownerId = insertUser(jdbc, "sw181o" + suffix, "김대표", "OWNER");
            long workerId = insertUser(jdbc, "sw181a" + suffix, "이알바", "WORKER");
            long otherWorkerId = insertUser(jdbc, "sw181b" + suffix, "박알바", "WORKER");

            long activeWorkplaceId = insertWorkplace(jdbc, ownerId, "강남점", 1, "ACTIVE");
            long inactiveWorkplaceId = insertWorkplace(jdbc, ownerId, "폐점", 2, "INACTIVE");

            // 후보: 본인 ACCEPTED, 본인 READY. 같은 사업장의 두 관계는 접히지 않고 두 행이다.
            long acceptedId = insertWorkCase(
                    jdbc, ownerId, workerId, activeWorkplaceId, "ACCEPTED", LATER_START, LATER_END);
            long readyId = insertWorkCase(
                    jdbc, ownerId, workerId, activeWorkplaceId, "READY",
                    EARLIER_START, EARLIER_END);

            // 후보 아님: IN_PROGRESS(접근 판정에서는 유효), 확정 전·후 상태, 비활성 사업장, 타인
            long inProgressId = insertWorkCase(
                    jdbc, ownerId, workerId, activeWorkplaceId, "IN_PROGRESS",
                    EARLIER_START, EARLIER_END);
            long completedId = insertWorkCase(
                    jdbc, ownerId, workerId, activeWorkplaceId, "COMPLETED",
                    EARLIER_START, EARLIER_END);
            long draftId = insertWorkCase(
                    jdbc, ownerId, workerId, activeWorkplaceId, "DRAFT",
                    EARLIER_START, EARLIER_END);
            long canceledId = insertWorkCase(
                    jdbc, ownerId, workerId, activeWorkplaceId, "CANCELED",
                    EARLIER_START, EARLIER_END);
            long inactiveWorkplaceCaseId = insertWorkCase(
                    jdbc, ownerId, workerId, inactiveWorkplaceId, "ACCEPTED",
                    EARLIER_START, EARLIER_END);
            long otherWorkerCaseId = insertWorkCase(
                    jdbc, ownerId, otherWorkerId, activeWorkplaceId, "ACCEPTED",
                    EARLIER_START, EARLIER_END);

            List<Long> workCaseIds = List.of(
                    acceptedId, readyId, inProgressId, completedId, draftId, canceledId,
                    inactiveWorkplaceCaseId, otherWorkerCaseId);

            try {
                ShareableWorkplaceListQuery query = ShareableWorkplaceListQuery.builder()
                        .workerId(workerId)
                        .size(20)
                        .offset(0)
                        .build();

                assertEquals(2L, mapper.countShareableWorkplaces(query));

                List<ShareableWorkplaceRow> rows = mapper.findShareableWorkplacePage(query);
                assertEquals(2, rows.size());

                // starts_at ASC이므로 READY(9/1)가 ACCEPTED(9/5)보다 먼저다.
                ShareableWorkplaceRow first = rows.get(0);
                assertEquals(activeWorkplaceId, first.getWorkplaceId());
                assertEquals("강남점", first.getWorkplaceName());
                assertEquals("김대표", first.getOwnerName());
                assertEquals(EARLIER_START, first.getStartsAt());
                assertEquals(EARLIER_END, first.getEndsAt());

                ShareableWorkplaceRow second = rows.get(1);
                assertEquals(activeWorkplaceId, second.getWorkplaceId());
                assertEquals(LATER_START, second.getStartsAt());
                assertEquals(LATER_END, second.getEndsAt());

                // 타 WORKER의 후보는 서로 보이지 않는다.
                ShareableWorkplaceListQuery otherQuery = ShareableWorkplaceListQuery.builder()
                        .workerId(otherWorkerId)
                        .size(20)
                        .offset(0)
                        .build();
                assertEquals(1L, mapper.countShareableWorkplaces(otherQuery));

                // 총계와 Page 창이 같은 조건을 본다.
                ShareableWorkplaceListQuery secondPage = ShareableWorkplaceListQuery.builder()
                        .workerId(workerId)
                        .size(1)
                        .offset(1)
                        .build();
                List<ShareableWorkplaceRow> tail = mapper.findShareableWorkplacePage(secondPage);
                assertEquals(1, tail.size());
                assertEquals(LATER_START, tail.get(0).getStartsAt());
                assertEquals(2L, mapper.countShareableWorkplaces(secondPage));

                assertTrue(mapper.findShareableWorkplacePage(
                        ShareableWorkplaceListQuery.builder()
                                .workerId(workerId).size(20).offset(2).build()).isEmpty());
            } finally {
                workCaseIds.forEach(id ->
                        jdbc.update("DELETE FROM work_cases WHERE id = ?", id));
                jdbc.update("DELETE FROM workplaces WHERE id IN (?, ?)",
                        activeWorkplaceId, inactiveWorkplaceId);
                jdbc.update("DELETE FROM users WHERE id IN (?, ?, ?)",
                        ownerId, workerId, otherWorkerId);
            }
        }
    }

    private long insertUser(JdbcTemplate jdbc, String loginId, String name, String role) {
        jdbc.update(
                "INSERT INTO users (login_id, email, password_hash, name, role, status)"
                        + " VALUES (?, ?, 'test-hash', ?, ?, 'ACTIVE')",
                loginId, loginId + "@example.test", name, role);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private long insertWorkplace(
            JdbcTemplate jdbc, long ownerId, String name, int sequence, String status) {
        jdbc.update(
                "INSERT INTO workplaces"
                        + " (owner_user_id, business_registration_number, name,"
                        + " representative_name, road_address, phone, status)"
                        + " VALUES (?, ?, ?, '김대표', '서울 테스트로 1', '0212345678', ?)",
                ownerId, businessNumberPrefix + String.format("%04d", sequence), name, status);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private long insertWorkCase(
            JdbcTemplate jdbc,
            long ownerId,
            long workerId,
            long workplaceId,
            String status,
            LocalDateTime startsAt,
            LocalDateTime endsAt) {
        // ck_work_cases_cancellation_lifecycle는 CANCELED에만 canceled_at을 요구하고
        // 나머지 상태에는 NULL을 요구한다.
        LocalDateTime canceledAt = "CANCELED".equals(status) ? startsAt.minusDays(1) : null;
        jdbc.update(
                "INSERT INTO work_cases"
                        + " (employer_id, worker_id, workplace_id, title, starts_at, ends_at,"
                        + " break_minutes, break_paid, workplace_name, workplace_address,"
                        + " allowed_radius_meters, agreed_wage, terms_version, status, canceled_at)"
                        + " VALUES (?, ?, ?, '공유 후보 테스트', ?, ?, 0, 0, '등록 시점 이름',"
                        + " '서울 테스트로 1', 100, 90000, 1, ?, ?)",
                ownerId, workerId, workplaceId, startsAt, endsAt, status, canceledAt);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }
}
