package com.gighub.workplace.mapper;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import javax.sql.DataSource;

import com.gighub.config.RootConfig;
import com.gighub.workplace.mapper.result.WorkplaceListRow;
import com.gighub.workplace.service.result.WorkplaceLocationSnapshot;
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
 * 실제 MySQL Head Schema에서 현장 위치 확정 SQL 계약을 검증합니다.
 *
 * <p>소유·활성 조건의 조합, {@code latitude IS NULL} 방어선, 목록 조회의
 * {@code attendanceLocationConfirmed} 파생값은 Java 단위 검증으로 대신할 수 없어 {@code
 * database} Tag의 Opt-in Test로 둡니다.</p>
 */
@Tag("database")
class WorkplaceCoordinateConfirmMapperDatabaseIntegrationTest {

    private static final BigDecimal LATITUDE = new BigDecimal("37.1234567");
    private static final BigDecimal LONGITUDE = new BigDecimal("127.1234567");

    private String businessNumberPrefix;

    @Test
    @Timeout(30)
    void confirmsCoordinatesOnlyOnceForOwnedActiveWorkplaceOnCurrentMysqlSchema() {
        try (AnnotationConfigApplicationContext context =
                     new AnnotationConfigApplicationContext(RootConfig.class)) {
            JdbcTemplate jdbcTemplate = new JdbcTemplate(context.getBean(DataSource.class));
            WorkplaceMapper workplaceMapper = context.getBean(WorkplaceMapper.class);

            String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
            businessNumberPrefix = String.format(
                    "%06d", ThreadLocalRandom.current().nextInt(1_000_000));
            Long ownerUserId = insertOwnerFixture(jdbcTemplate, suffix + "o");
            Long otherOwnerUserId = insertOwnerFixture(jdbcTemplate, suffix + "x");

            try {
                Long active = insertWorkplace(jdbcTemplate, ownerUserId, businessNumber(1), "ACTIVE");
                Long inactive = insertWorkplace(jdbcTemplate, ownerUserId, businessNumber(2), "INACTIVE");

                verifyLockOnlyMatchesOwnedActive(
                        workplaceMapper, ownerUserId, otherOwnerUserId, active, inactive);
                verifyConfirmGuardsAgainstOverwrite(workplaceMapper, active);
                verifyListReflectsConfirmedState(workplaceMapper, ownerUserId, active, inactive);
            } finally {
                jdbcTemplate.update(
                        "DELETE FROM workplaces WHERE owner_user_id IN (?, ?)",
                        ownerUserId, otherOwnerUserId);
                jdbcTemplate.update(
                        "DELETE FROM users WHERE id IN (?, ?)", ownerUserId, otherOwnerUserId);
            }
        }
    }

    /**
     * 다른 OWNER와 비활성 사업장은 존재 여부를 구분하지 않고 모두 {@code null}이어야
     * 합니다. 구분하면 사업장 식별자의 존재가 비소유자에게 드러납니다.
     */
    private void verifyLockOnlyMatchesOwnedActive(
            WorkplaceMapper workplaceMapper,
            Long ownerUserId,
            Long otherOwnerUserId,
            Long active,
            Long inactive) {
        WorkplaceLocationSnapshot snapshot =
                workplaceMapper.findOwnedActiveLocationForUpdate(active, ownerUserId);
        assertEquals(active, snapshot.workplaceId());
        assertFalse(snapshot.hasCoordinates(), "새로 만든 사업장은 좌표가 비어 있어야 합니다.");

        assertNull(
                workplaceMapper.findOwnedActiveLocationForUpdate(active, otherOwnerUserId),
                "다른 OWNER는 잠글 수 없어야 합니다.");
        assertNull(
                workplaceMapper.findOwnedActiveLocationForUpdate(inactive, ownerUserId),
                "INACTIVE 사업장은 잠글 수 없어야 합니다.");
    }

    /**
     * {@code latitude IS NULL} 조건이 실제 SQL 방어선인지 확인합니다.
     *
     * <p>첫 확정은 행을 갱신하고, 이미 확정된 행에 같은 SQL을 다시 실행하면 영향받은 행
     * 수가 0이어야 합니다 — 서비스 계층의 판단과 무관하게 SQL 자체가 덮어쓰지 않습니다.</p>
     */
    private void verifyConfirmGuardsAgainstOverwrite(WorkplaceMapper workplaceMapper, Long active) {
        assertEquals(1, workplaceMapper.confirmCoordinates(active, LATITUDE, LONGITUDE));

        BigDecimal differentLatitude = new BigDecimal("1.0000000");
        assertEquals(
                0,
                workplaceMapper.confirmCoordinates(active, differentLatitude, LONGITUDE),
                "이미 확정된 행은 다시 갱신되지 않아야 합니다.");

        WorkplaceLocationSnapshot afterOverwriteAttempt =
                workplaceMapper.findActiveLocationForUpdate(active);
        assertEquals(
                0,
                LATITUDE.compareTo(afterOverwriteAttempt.latitude()),
                "덮어쓰기 시도 뒤에도 최초 확정값이 그대로여야 합니다.");
    }

    /** 목록 조회의 파생값이 실제 컬럼 상태를 따라가는지 확인합니다. */
    private void verifyListReflectsConfirmedState(
            WorkplaceMapper workplaceMapper, Long ownerUserId, Long active, Long inactive) {
        List<WorkplaceListRow> rows = workplaceMapper.findPageByOwnerUserId(ownerUserId, 100, 0L);

        WorkplaceListRow confirmedRow = rows.stream()
                .filter(row -> active.equals(row.getWorkplaceId()))
                .findFirst()
                .orElseThrow();
        WorkplaceListRow unconfirmedRow = rows.stream()
                .filter(row -> inactive.equals(row.getWorkplaceId()))
                .findFirst()
                .orElseThrow();

        assertTrue(
                confirmedRow.isAttendanceLocationConfirmed(),
                "confirmCoordinates 이후에는 목록에서도 확정으로 보여야 합니다.");
        assertFalse(
                unconfirmedRow.isAttendanceLocationConfirmed(),
                "좌표가 없는 사업장은 목록에서도 미확정이어야 합니다.");
    }

    private Long insertWorkplace(
            JdbcTemplate jdbcTemplate, Long ownerUserId, String businessRegistrationNumber, String status) {
        jdbcTemplate.update(
                "INSERT INTO workplaces "
                        + "(owner_user_id, business_registration_number, name, representative_name, "
                        + " road_address, phone, radius_meters, status) "
                        + "VALUES (?, ?, '강남점', '김사장', '서울 강남구 테헤란로 1', '0212345678', "
                        + " 100.00, ?)",
                ownerUserId, businessRegistrationNumber, status);

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
                "qa350" + suffix,
                "qa350" + suffix + "@example.com",
                "$2a$10$0000000000000000000000000000000000000000000000000000",
                "김사장");

        return jdbcTemplate.queryForObject(
                "SELECT id FROM users WHERE login_id = ?", Long.class, "qa350" + suffix);
    }
}
