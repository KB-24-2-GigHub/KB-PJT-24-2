package com.gighub.workplace.mapper;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import javax.sql.DataSource;

import com.gighub.config.RootConfig;
import com.gighub.workplace.mapper.param.WorkplaceUpdateParam;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 실제 MySQL Head Schema에서 사업장 부분 수정 SQL 계약을 검증합니다(SPEC-349-01).
 *
 * <p>동적 {@code <set>}이 보낸 Column만 바꾸는지, 상세주소를 {@code NULL}로 지울 수 있는지,
 * 주소 조건이 좌표 덮어쓰기를 실제로 막는지는 Java 단위 검증으로 대신할 수 없어 {@code
 * database} Tag의 Opt-in Test로 둡니다.</p>
 */
@Tag("database")
class WorkplaceUpdateMapperDatabaseIntegrationTest {

    private static final BigDecimal LATITUDE = new BigDecimal("37.1234567");
    private static final BigDecimal LONGITUDE = new BigDecimal("127.1234567");

    private String businessNumberPrefix;

    @Test
    @Timeout(30)
    void updatesOnlyProvidedColumnsAndGuardsCoordinateOverwriteOnCurrentMysqlSchema() {
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

                verifyOwnershipAndStatusBoundary(
                        workplaceMapper, ownerUserId, otherOwnerUserId, active, inactive);
                verifyPartialUpdateLeavesOtherColumns(workplaceMapper, jdbcTemplate, ownerUserId, active);
                verifyDetailAddressCanBeCleared(workplaceMapper, jdbcTemplate, ownerUserId, active);
                verifyAddressAndCoordinatesMoveTogether(
                        workplaceMapper, jdbcTemplate, ownerUserId, active);
                verifyStaleAddressGuardRejectsUpdate(
                        workplaceMapper, jdbcTemplate, ownerUserId, active);
            } finally {
                jdbcTemplate.update(
                        "DELETE FROM workplaces WHERE owner_user_id IN (?, ?)",
                        ownerUserId, otherOwnerUserId);
                jdbcTemplate.update(
                        "DELETE FROM users WHERE id IN (?, ?)", ownerUserId, otherOwnerUserId);
            }
        }
    }

    /** 주소 조회와 갱신 모두 없는 사업장·다른 OWNER·비활성을 구분하지 않아야 합니다. */
    private void verifyOwnershipAndStatusBoundary(
            WorkplaceMapper workplaceMapper,
            Long ownerUserId,
            Long otherOwnerUserId,
            Long active,
            Long inactive) {
        assertEquals(
                "서울 강남구 테헤란로 1",
                workplaceMapper.findOwnedActiveRoadAddress(active, ownerUserId));
        assertNull(workplaceMapper.findOwnedActiveRoadAddress(active, otherOwnerUserId));
        assertNull(workplaceMapper.findOwnedActiveRoadAddress(inactive, ownerUserId));

        assertEquals(
                0,
                workplaceMapper.updateOwnedActive(nameChange(active, otherOwnerUserId, "탈취점")),
                "다른 OWNER는 갱신할 수 없어야 합니다.");
        assertEquals(
                0,
                workplaceMapper.updateOwnedActive(nameChange(inactive, ownerUserId, "비활성점")),
                "INACTIVE 사업장은 갱신할 수 없어야 합니다.");
    }

    /** 동적 {@code <set>}이 보내지 않은 Column을 건드리면 조용한 데이터 손실이 됩니다. */
    private void verifyPartialUpdateLeavesOtherColumns(
            WorkplaceMapper workplaceMapper, JdbcTemplate jdbcTemplate, Long ownerUserId, Long active) {
        assertEquals(1, workplaceMapper.updateOwnedActive(nameChange(active, ownerUserId, "강남 2호점")));

        Map<String, Object> row = selectWorkplace(jdbcTemplate, active);
        assertEquals("강남 2호점", row.get("name"));
        assertEquals("서울 강남구 테헤란로 1", row.get("road_address"));
        assertEquals("2층", row.get("detail_address"));
        assertEquals("0212345678", row.get("phone"));
        assertEquals("김사장", row.get("representative_name"));
        assertNull(row.get("latitude"), "좌표를 보내지 않은 수정은 좌표를 채우지 않아야 합니다.");
    }

    /** 상세주소는 값이 아니라 존재 여부로 판단하므로 {@code NULL}로 지워져야 합니다. */
    private void verifyDetailAddressCanBeCleared(
            WorkplaceMapper workplaceMapper, JdbcTemplate jdbcTemplate, Long ownerUserId, Long active) {
        assertEquals(1, workplaceMapper.updateOwnedActive(WorkplaceUpdateParam.builder()
                .workplaceId(active)
                .ownerUserId(ownerUserId)
                .detailAddressProvided(true)
                .detailAddress(null)
                .build()));

        assertNull(selectWorkplace(jdbcTemplate, active).get("detail_address"));
    }

    /** 주소와 좌표가 갈라지면 반경 판정 기준점이 실제 사업장과 다른 지점이 됩니다. */
    private void verifyAddressAndCoordinatesMoveTogether(
            WorkplaceMapper workplaceMapper, JdbcTemplate jdbcTemplate, Long ownerUserId, Long active) {
        assertEquals(1, workplaceMapper.updateOwnedActive(WorkplaceUpdateParam.builder()
                .workplaceId(active)
                .ownerUserId(ownerUserId)
                .roadAddressProvided(true)
                .roadAddress("서울 강남구 테헤란로 2")
                .latitude(LATITUDE)
                .longitude(LONGITUDE)
                .expectedRoadAddress("서울 강남구 테헤란로 1")
                .build()));

        Map<String, Object> row = selectWorkplace(jdbcTemplate, active);
        assertEquals("서울 강남구 테헤란로 2", row.get("road_address"));
        assertEquals(0, LATITUDE.compareTo((BigDecimal) row.get("latitude")));
        assertEquals(0, LONGITUDE.compareTo((BigDecimal) row.get("longitude")));
    }

    /**
     * 변환 근거였던 주소가 이미 바뀌었으면 갱신이 0행이어야 합니다.
     *
     * <p>주소 변환은 트랜잭션 밖에서 끝나므로 이 조건이 유일한 방어선입니다. 조건이 빠지면
     * 이전 주소로 구한 좌표가 새 주소 위에 조용히 덮입니다.</p>
     */
    private void verifyStaleAddressGuardRejectsUpdate(
            WorkplaceMapper workplaceMapper, JdbcTemplate jdbcTemplate, Long ownerUserId, Long active) {
        BigDecimal staleLatitude = new BigDecimal("1.0000000");

        assertEquals(0, workplaceMapper.updateOwnedActive(WorkplaceUpdateParam.builder()
                .workplaceId(active)
                .ownerUserId(ownerUserId)
                .roadAddressProvided(true)
                .roadAddress("서울 강남구 테헤란로 3")
                .latitude(staleLatitude)
                .longitude(LONGITUDE)
                // 직전 검증이 주소를 "테헤란로 2"로 바꿔 놓았으므로 이 기대값은 이미 낡았습니다.
                .expectedRoadAddress("서울 강남구 테헤란로 1")
                .build()));

        Map<String, Object> row = selectWorkplace(jdbcTemplate, active);
        assertEquals("서울 강남구 테헤란로 2", row.get("road_address"));
        assertNotEquals(0, staleLatitude.compareTo((BigDecimal) row.get("latitude")));
    }

    private WorkplaceUpdateParam nameChange(Long workplaceId, Long ownerUserId, String name) {
        return WorkplaceUpdateParam.builder()
                .workplaceId(workplaceId)
                .ownerUserId(ownerUserId)
                .nameProvided(true)
                .name(name)
                .build();
    }

    private Map<String, Object> selectWorkplace(JdbcTemplate jdbcTemplate, Long workplaceId) {
        return jdbcTemplate.queryForMap(
                "SELECT name, road_address, detail_address, phone, representative_name, "
                        + " latitude, longitude FROM workplaces WHERE id = ?",
                workplaceId);
    }

    private Long insertWorkplace(
            JdbcTemplate jdbcTemplate, Long ownerUserId, String businessRegistrationNumber, String status) {
        jdbcTemplate.update(
                "INSERT INTO workplaces "
                        + "(owner_user_id, business_registration_number, name, representative_name, "
                        + " road_address, detail_address, phone, radius_meters, status) "
                        + "VALUES (?, ?, '강남점', '김사장', '서울 강남구 테헤란로 1', '2층', "
                        + " '0212345678', 100.00, ?)",
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
                "qa349" + suffix,
                "qa349" + suffix + "@example.com",
                "$2a$10$0000000000000000000000000000000000000000000000000000",
                "김사장");

        return jdbcTemplate.queryForObject(
                "SELECT id FROM users WHERE login_id = ?", Long.class, "qa349" + suffix);
    }
}
