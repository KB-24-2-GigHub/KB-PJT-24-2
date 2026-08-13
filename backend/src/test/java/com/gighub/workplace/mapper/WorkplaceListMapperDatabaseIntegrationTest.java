package com.gighub.workplace.mapper;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import javax.sql.DataSource;

import com.gighub.config.RootConfig;
import com.gighub.workplace.mapper.result.WorkplaceListRow;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 실제 MySQL Head Schema에서 OWNER 사업장 목록 조회 계약을 검증합니다.
 *
 * <p>노출 범위(`ACTIVE`·`INACTIVE`만), 소유자 격리, Page 경계와 정렬 안정성은 Java 단위
 * 검증으로 대신할 수 없어 {@code database} Tag의 Opt-in Test로 둡니다.</p>
 *
 * <p>{@code INACTIVE}와 {@code DELETED}를 만드는 API는 아직 없으므로 Fixture를 JdbcTemplate으로
 * 직접 넣습니다. {@code created_at}도 직접 지정합니다. 기본값에 맡기면 모든 행이 사실상 같은
 * 시각이 되어 정렬 검증이 우연히 통과합니다.</p>
 */
@Tag("database")
class WorkplaceListMapperDatabaseIntegrationTest {

    private static final LocalDateTime OLDEST = LocalDateTime.of(2026, 1, 1, 10, 0, 0);
    private static final LocalDateTime MIDDLE = LocalDateTime.of(2026, 1, 2, 10, 0, 0);
    private static final LocalDateTime NEWEST = LocalDateTime.of(2026, 1, 3, 10, 0, 0);

    private String businessNumberPrefix;

    @Test
    @Timeout(30)
    void returnsOnlyOwnedManageableWorkplacesInStableOrderOnCurrentMysqlSchema() {
        try (AnnotationConfigApplicationContext context =
                     new AnnotationConfigApplicationContext(RootConfig.class)) {
            JdbcTemplate jdbcTemplate = new JdbcTemplate(context.getBean(DataSource.class));
            WorkplaceMapper workplaceMapper = context.getBean(WorkplaceMapper.class);

            String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
            // 사업자등록번호는 Unique 숫자 10자리이므로 앞 6자리를 실행마다 다르게 만듭니다.
            // 고정값을 쓰면 이전 실행이 정리 전에 중단됐을 때 다음 실행이 엉뚱한 중복으로 실패합니다.
            businessNumberPrefix = String.format(
                    "%06d", ThreadLocalRandom.current().nextInt(1_000_000));
            Long ownerUserId = insertOwnerFixture(jdbcTemplate, suffix + "a");
            Long otherOwnerUserId = insertOwnerFixture(jdbcTemplate, suffix + "b");
            Long emptyOwnerUserId = insertOwnerFixture(jdbcTemplate, suffix + "c");

            try {
                Fixture fixture = insertWorkplaceFixture(jdbcTemplate, ownerUserId, otherOwnerUserId);

                verifyExcludesDeletedAndOtherOwner(workplaceMapper, ownerUserId, fixture);
                verifyOrderIsStableOnEqualCreatedAt(workplaceMapper, ownerUserId, fixture);
                verifyRowCarriesApprovedColumns(workplaceMapper, ownerUserId, fixture);
                verifyPageBoundaries(workplaceMapper, ownerUserId, fixture);
                verifyEmptyOwnerReturnsEmptyPage(workplaceMapper, emptyOwnerUserId);
            } finally {
                jdbcTemplate.update(
                        "DELETE FROM workplaces WHERE owner_user_id IN (?, ?, ?)",
                        ownerUserId, otherOwnerUserId, emptyOwnerUserId);
                jdbcTemplate.update(
                        "DELETE FROM users WHERE id IN (?, ?, ?)",
                        ownerUserId, otherOwnerUserId, emptyOwnerUserId);
            }
        }
    }

    /**
     * 목록과 건수 모두 소유 {@code ACTIVE}·{@code INACTIVE}만 세야 합니다.
     *
     * <p>{@code DELETED}가 새는지, 다른 OWNER의 사업장이 섞이는지는 응답만 봐서는 구분되지
     * 않으므로 두 오염원을 같은 Fixture에 함께 두고 확인합니다.</p>
     */
    private void verifyExcludesDeletedAndOtherOwner(
            WorkplaceMapper workplaceMapper,
            Long ownerUserId,
            Fixture fixture) {
        List<WorkplaceListRow> rows = workplaceMapper.findPageByOwnerUserId(ownerUserId, 100, 0L);

        assertEquals(
                List.of(fixture.newestActive, fixture.middleActive, fixture.middleInactive, fixture.oldestActive),
                rows.stream().map(WorkplaceListRow::getWorkplaceId).toList(),
                "소유한 ACTIVE·INACTIVE만 최신 등록 순으로 반환해야 합니다.");
        assertEquals(4, workplaceMapper.countByOwnerUserId(ownerUserId), "건수는 목록과 같은 조건이어야 합니다.");
        assertEquals(
                3,
                workplaceMapper.countActiveByOwnerUserId(ownerUserId),
                "온보딩 건수는 INACTIVE를 제외한 ACTIVE 사업장만 세어야 합니다.");

        assertTrue(
                rows.stream().noneMatch(row -> "DELETED".equals(row.getStatus())),
                "DELETED 사업장은 노출되지 않아야 합니다.");
    }

    /**
     * {@code created_at}이 같은 두 행의 순서가 흔들리면 Page 사이에서 행이 중복되거나 누락됩니다.
     *
     * <p>{@code id} Tie-breaker가 실제로 순서를 결정하는지 확인합니다. 같은 시각의 두 행 중 나중에
     * 등록한 쪽이 항상 앞이어야 합니다.</p>
     */
    private void verifyOrderIsStableOnEqualCreatedAt(
            WorkplaceMapper workplaceMapper,
            Long ownerUserId,
            Fixture fixture) {
        List<Long> ids = workplaceMapper.findPageByOwnerUserId(ownerUserId, 100, 0L)
                .stream()
                .map(WorkplaceListRow::getWorkplaceId)
                .toList();

        assertTrue(
                fixture.middleActive > fixture.middleInactive,
                "Fixture 전제: 같은 시각 행 중 middleActive가 나중에 등록돼야 합니다.");
        assertTrue(
                ids.indexOf(fixture.middleActive) < ids.indexOf(fixture.middleInactive),
                "created_at이 같으면 id가 큰 행이 앞이어야 합니다.");
    }

    /** 승인된 목록 Item 값이 그대로 실려야 하고, 선택 컬럼의 NULL도 그대로 와야 합니다. */
    private void verifyRowCarriesApprovedColumns(
            WorkplaceMapper workplaceMapper,
            Long ownerUserId,
            Fixture fixture) {
        List<WorkplaceListRow> rows = workplaceMapper.findPageByOwnerUserId(ownerUserId, 100, 0L);

        WorkplaceListRow newest = rowOf(rows, fixture.newestActive);
        assertEquals(businessNumber(1), newest.getBusinessRegistrationNumber());
        assertEquals("강남점", newest.getName());
        assertEquals("김사장", newest.getRepresentativeName());
        assertEquals("서울 강남구 테헤란로 1", newest.getRoadAddress());
        assertEquals("2층", newest.getDetailAddress());
        assertEquals("0212345678", newest.getPhone());
        assertEquals(0, new BigDecimal("100.00").compareTo(newest.getRadiusMeters()));
        assertEquals("ACTIVE", newest.getStatus());

        WorkplaceListRow inactive = rowOf(rows, fixture.middleInactive);
        assertEquals("INACTIVE", inactive.getStatus(), "INACTIVE 사업장은 상태를 그대로 노출해야 합니다.");
        assertNull(inactive.getDetailAddress(), "선택 컬럼의 NULL은 그대로 와야 합니다.");

        // Fixture 어느 행도 좌표를 넣지 않으므로 모두 미확정으로 파생돼야 합니다. 확정된
        // 사업장과의 조합은 WorkplaceCoordinateConfirmMapperDatabaseIntegrationTest가 다룹니다.
        assertFalse(
                newest.isAttendanceLocationConfirmed(),
                "좌표를 넣지 않은 사업장은 미확정으로 파생돼야 합니다.");
    }

    /**
     * {@code LIMIT}·{@code OFFSET}이 정렬된 전체 결과를 겹침 없이 잘라야 합니다.
     *
     * <p>마지막 Page 다음을 요청하면 오류가 아니라 빈 목록입니다.</p>
     */
    private void verifyPageBoundaries(
            WorkplaceMapper workplaceMapper,
            Long ownerUserId,
            Fixture fixture) {
        assertEquals(
                List.of(fixture.newestActive, fixture.middleActive),
                pageIds(workplaceMapper, ownerUserId, 2, 0L),
                "첫 Page는 앞의 두 건이어야 합니다.");
        assertEquals(
                List.of(fixture.middleInactive, fixture.oldestActive),
                pageIds(workplaceMapper, ownerUserId, 2, 2L),
                "다음 Page는 겹치지 않고 이어져야 합니다.");
        assertEquals(
                List.of(),
                pageIds(workplaceMapper, ownerUserId, 2, 4L),
                "마지막 Page 다음은 빈 목록이어야 합니다.");
    }

    /** 사업장이 없는 OWNER는 오류가 아니라 빈 Page입니다. 첫 등록 전 상태가 여기에 해당합니다. */
    private void verifyEmptyOwnerReturnsEmptyPage(WorkplaceMapper workplaceMapper, Long emptyOwnerUserId) {
        assertEquals(List.of(), workplaceMapper.findPageByOwnerUserId(emptyOwnerUserId, 20, 0L));
        assertEquals(0, workplaceMapper.countByOwnerUserId(emptyOwnerUserId));
        assertEquals(0, workplaceMapper.countActiveByOwnerUserId(emptyOwnerUserId));
    }

    private List<Long> pageIds(WorkplaceMapper workplaceMapper, Long ownerUserId, int size, long offset) {
        return workplaceMapper.findPageByOwnerUserId(ownerUserId, size, offset)
                .stream()
                .map(WorkplaceListRow::getWorkplaceId)
                .toList();
    }

    private WorkplaceListRow rowOf(List<WorkplaceListRow> rows, Long workplaceId) {
        return rows.stream()
                .filter(row -> workplaceId.equals(row.getWorkplaceId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("목록에 있어야 할 사업장이 없습니다: " + workplaceId));
    }

    /**
     * 노출 대상 4건과 오염원 2건을 넣습니다.
     *
     * <p>{@code middleInactive}와 {@code middleActive}는 {@code created_at}이 같아 Tie-breaker
     * 검증용이며, 등록 순서 때문에 {@code middleActive}의 {@code id}가 더 큽니다.</p>
     */
    private Fixture insertWorkplaceFixture(
            JdbcTemplate jdbcTemplate,
            Long ownerUserId,
            Long otherOwnerUserId) {
        Fixture fixture = new Fixture();
        fixture.newestActive = insertWorkplace(
                jdbcTemplate, ownerUserId, businessNumber(1), "2층", "ACTIVE", NEWEST);
        fixture.middleInactive = insertWorkplace(
                jdbcTemplate, ownerUserId, businessNumber(2), null, "INACTIVE", MIDDLE);
        fixture.middleActive = insertWorkplace(
                jdbcTemplate, ownerUserId, businessNumber(3), "3층", "ACTIVE", MIDDLE);
        fixture.oldestActive = insertWorkplace(
                jdbcTemplate, ownerUserId, businessNumber(4), "4층", "ACTIVE", OLDEST);

        insertWorkplace(jdbcTemplate, ownerUserId, businessNumber(5), "5층", "DELETED", NEWEST);
        insertWorkplace(jdbcTemplate, otherOwnerUserId, businessNumber(6), "6층", "ACTIVE", NEWEST);

        return fixture;
    }

    /** {@code deleted_at}은 {@code ck_workplaces_deleted_at}이 DELETED와 1:1로 묶어 둡니다. */
    private Long insertWorkplace(
            JdbcTemplate jdbcTemplate,
            Long ownerUserId,
            String businessRegistrationNumber,
            String detailAddress,
            String status,
            LocalDateTime createdAt) {
        Timestamp deletedAt = "DELETED".equals(status) ? Timestamp.valueOf(createdAt) : null;

        jdbcTemplate.update(
                "INSERT INTO workplaces "
                        + "(owner_user_id, business_registration_number, name, representative_name, "
                        + " road_address, detail_address, phone, radius_meters, status, deleted_at, created_at) "
                        + "VALUES (?, ?, '강남점', '김사장', '서울 강남구 테헤란로 1', ?, '0212345678', "
                        + " 100.00, ?, ?, ?)",
                ownerUserId,
                businessRegistrationNumber,
                detailAddress,
                status,
                deletedAt,
                Timestamp.valueOf(createdAt));

        return jdbcTemplate.queryForObject(
                "SELECT id FROM workplaces WHERE business_registration_number = ?",
                Long.class,
                businessRegistrationNumber);
    }

    private String businessNumber(int index) {
        return businessNumberPrefix + String.format("%04d", index);
    }

    private Long insertOwnerFixture(JdbcTemplate jdbcTemplate, String suffix) {
        jdbcTemplate.update(
                "INSERT INTO users (login_id, email, password_hash, name, role) "
                        + "VALUES (?, ?, ?, ?, 'OWNER')",
                "qa145" + suffix,
                "qa145" + suffix + "@example.com",
                "$2a$10$0000000000000000000000000000000000000000000000000000",
                "김사장");

        return jdbcTemplate.queryForObject(
                "SELECT id FROM users WHERE login_id = ?", Long.class, "qa145" + suffix);
    }

    /** Fixture가 만든 사업장 식별자입니다. 이름은 정렬 기준(등록 시각)과 상태를 함께 나타냅니다. */
    private static final class Fixture {
        private Long newestActive;
        private Long middleInactive;
        private Long middleActive;
        private Long oldestActive;
    }
}
