package com.gighub.attendance;

import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import javax.sql.DataSource;

import com.gighub.auth.security.AuthPrincipal;
import com.gighub.config.RootConfig;
import com.gighub.workplace.geocoding.FixedAddressGeocoderConfig;
import com.gighub.member.domain.UserRole;
import com.gighub.workplace.mapper.WorkplaceMapper;
import com.gighub.workplace.mapper.param.WorkplaceInsertParam;
import com.gighub.workplace.service.WorkplaceService;
import com.gighub.workplace.service.command.WorkplaceCreateCommand;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 고정 QR의 접근 경계와 발급 원자성을 실제 MySQL에서 검증합니다.
 *
 * <p>Service 단위 검증은 Mapper를 Mock으로 대체하므로 소유권·상태 조건이 SQL에서 빠져도
 * 통과합니다. 그 조건은 남의 사업장 QR을 막는 경계이므로 실제 Query로 확인합니다.</p>
 *
 * <p>발급이 호출자 트랜잭션에 참여한다는 규칙도 주석이 아니라 Rollback 동작으로 고정합니다.
 * 발급이 별도 트랜잭션을 열면 사업장 없이 QR만 남습니다.</p>
 */
@Tag("database")
class WorkplaceQrAccessDatabaseIntegrationTest {

    @Test
    @Timeout(60)
    void ownershipQueriesAcceptOnlyTheOwnersActiveWorkplace() {
        try (AnnotationConfigApplicationContext context =
                     new AnnotationConfigApplicationContext(
                             RootConfig.class, FixedAddressGeocoderConfig.class)) {
            JdbcTemplate jdbcTemplate = new JdbcTemplate(context.getBean(DataSource.class));
            WorkplaceMapper workplaceMapper = context.getBean(WorkplaceMapper.class);

            String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
            Long ownerUserId = insertOwnerFixture(jdbcTemplate, "a" + suffix.substring(1));
            Long strangerUserId = insertOwnerFixture(jdbcTemplate, "b" + suffix.substring(1));
            Long workplaceId = insertWorkplaceFixture(workplaceMapper, ownerUserId);

            try {
                assertEquals(1, workplaceMapper.countOwnedActiveById(workplaceId, ownerUserId));
                assertEquals(workplaceId,
                        workplaceMapper.findOwnedActiveIdForUpdate(workplaceId, ownerUserId));

                // 다른 OWNER는 같은 식별자로도 통과하지 못합니다.
                assertEquals(0, workplaceMapper.countOwnedActiveById(workplaceId, strangerUserId));
                assertNull(workplaceMapper.findOwnedActiveIdForUpdate(workplaceId, strangerUserId));

                // 없는 사업장도 같은 결과입니다. 둘을 구분하면 존재 여부가 드러납니다.
                assertEquals(0, workplaceMapper.countOwnedActiveById(-1L, ownerUserId));
                assertNull(workplaceMapper.findOwnedActiveIdForUpdate(-1L, ownerUserId));

                // ACTIVE가 아닌 사업장에는 고정 QR이 없습니다.
                jdbcTemplate.update(
                        "UPDATE workplaces SET status = 'INACTIVE' WHERE id = ?", workplaceId);
                assertEquals(0, workplaceMapper.countOwnedActiveById(workplaceId, ownerUserId));
                assertNull(workplaceMapper.findOwnedActiveIdForUpdate(workplaceId, ownerUserId));
            } finally {
                jdbcTemplate.update("DELETE FROM qr_tokens WHERE workplace_id = ?", workplaceId);
                jdbcTemplate.update("DELETE FROM workplaces WHERE id = ?", workplaceId);
                jdbcTemplate.update("DELETE FROM users WHERE id IN (?, ?)",
                        ownerUserId, strangerUserId);
            }
        }
    }

    /** 사업장 생성이 Rollback되면 그 사업장의 QR도 남지 않아야 합니다. */
    @Test
    @Timeout(60)
    void rollingBackWorkplaceCreationLeavesNoOrphanQr() {
        try (AnnotationConfigApplicationContext context =
                     new AnnotationConfigApplicationContext(
                             RootConfig.class, FixedAddressGeocoderConfig.class)) {
            JdbcTemplate jdbcTemplate = new JdbcTemplate(context.getBean(DataSource.class));
            WorkplaceService workplaceService = context.getBean(WorkplaceService.class);
            TransactionTemplate transactionTemplate = new TransactionTemplate(
                    context.getBean(PlatformTransactionManager.class));

            String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
            Long ownerUserId = insertOwnerFixture(jdbcTemplate, "c" + suffix.substring(1));
            String businessNumber = String.format(
                    "%010d", ThreadLocalRandom.current().nextLong(1_000_000_000L));

            try {
                Long createdId = transactionTemplate.execute(status -> {
                    Long workplaceId = workplaceService.create(
                            new AuthPrincipal(ownerUserId, UserRole.OWNER, "김사장"),
                            command(businessNumber));
                    status.setRollbackOnly();
                    return workplaceId;
                });

                assertNotNull(createdId);
                assertEquals(0, jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM workplaces WHERE id = ?",
                        Integer.class, createdId));
                // 발급이 별도 트랜잭션을 열면 사업장 없이 QR만 남습니다.
                assertEquals(0, jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM qr_tokens WHERE workplace_id = ?",
                        Integer.class, createdId));
            } finally {
                jdbcTemplate.update("DELETE FROM users WHERE id = ?", ownerUserId);
            }
        }
    }

    private static WorkplaceCreateCommand command(String businessRegistrationNumber) {
        return WorkplaceCreateCommand.builder()
                .businessRegistrationNumber(businessRegistrationNumber)
                .name("강남점")
                .representativeName("김사장")
                .roadAddress("서울 강남구 테헤란로 1")
                .detailAddress("2층")
                .phone("0212345678")
                .build();
    }

    private Long insertOwnerFixture(JdbcTemplate jdbcTemplate, String suffix) {
        jdbcTemplate.update(
                "INSERT INTO users (login_id, email, password_hash, name, role) "
                        + "VALUES (?, ?, ?, ?, 'OWNER')",
                "qr162x" + suffix,
                "qr162x" + suffix + "@example.com",
                "$2a$10$0000000000000000000000000000000000000000000000000000",
                "김사장");

        return jdbcTemplate.queryForObject(
                "SELECT id FROM users WHERE login_id = ?", Long.class, "qr162x" + suffix);
    }

    private Long insertWorkplaceFixture(WorkplaceMapper workplaceMapper, Long ownerUserId) {
        WorkplaceInsertParam param = WorkplaceInsertParam.builder()
                .ownerUserId(ownerUserId)
                .businessRegistrationNumber(String.format(
                        "%010d", ThreadLocalRandom.current().nextLong(1_000_000_000L)))
                .name("강남점")
                .representativeName("김사장")
                .roadAddress("서울 강남구 테헤란로 1")
                .detailAddress("2층")
                .phone("0212345678")
                .build();

        workplaceMapper.insert(param);
        return param.getId();
    }
}
