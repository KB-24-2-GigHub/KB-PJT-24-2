package com.gighub.notification;

import com.gighub.config.RootConfig;
import com.gighub.notification.domain.NotificationSourceType;
import com.gighub.notification.domain.NotificationType;
import com.gighub.notification.mapper.NotificationMapper;
import com.gighub.notification.mapper.command.NotificationInsert;
import com.gighub.notification.mapper.result.NotificationRow;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Mapper XML의 SQL이 실제 Schema에서 동작하는지 확인한다.
 *
 * <p>여기서 보는 것은 세 가지다. 목록이 수신자 본인 것만 최신순으로 나오는지, 읽음 처리가 본인
 * 알림만 바꾸고 재요청에도 최초 읽은 시각을 지키는지, 안읽음 개수가 저장된 상태와 일치하는지다.
 * 제약 자체는 {@code NotificationSchemaDatabaseIntegrationTest}가 담당한다.</p>
 */
@Tag("database")
class NotificationMapperDatabaseIntegrationTest {

    @Test
    @Timeout(60)
    void servesRecipientScopedListAndReadState() {
        try (AnnotationConfigApplicationContext context =
                     new AnnotationConfigApplicationContext(RootConfig.class)) {
            NotificationMapper mapper = context.getBean(NotificationMapper.class);
            JdbcTemplate jdbcTemplate = new JdbcTemplate(context.getBean(DataSource.class));
            String token = UUID.randomUUID().toString().replace("-", "");
            String workerLoginId = "it_notimap_w_" + token;
            String ownerLoginId = "it_notimap_o_" + token;

            Long workCaseId = null;
            Long workplaceId = null;
            try {
                long workerId = insertUser(jdbcTemplate, workerLoginId, token + "w", "WORKER");
                long ownerId = insertUser(jdbcTemplate, ownerLoginId, token + "o", "OWNER");
                workplaceId = insertWorkplace(jdbcTemplate, ownerId, token);
                workCaseId = insertWorkCase(jdbcTemplate, ownerId, workerId, workplaceId, token);

                insert(mapper, workerId, NotificationType.WORK_CASE_CONFIRMED,
                        workCaseId, workCaseId);
                insert(mapper, workerId, NotificationType.SETTLED, 8801L, workCaseId);
                insert(mapper, ownerId, NotificationType.SETTLED, 8801L, workCaseId);

                List<NotificationRow> workerPage =
                        mapper.findPageByRecipient(workerId, 20, 0, false);
                assertEquals(2, workerPage.size(), "본인 알림만 나와야 합니다.");
                // 최신순이므로 나중에 넣은 SETTLED가 앞에 온다.
                assertEquals(NotificationType.SETTLED.name(), workerPage.get(0).getNotiType());
                assertEquals(
                        NotificationSourceType.SETTLEMENT.name(),
                        workerPage.get(0).getSourceType());
                assertEquals(workCaseId, workerPage.get(0).getWorkCaseId());
                assertFalse(workerPage.get(0).getIsRead());
                assertNull(workerPage.get(0).getReadAt());
                assertEquals(2, mapper.countByRecipient(workerId, false));
                assertEquals(2, mapper.countUnreadByRecipient(workerId));

                long targetId = workerPage.get(0).getNotificationId();
                assertEquals(1, mapper.markRead(targetId, workerId));
                assertEquals(1, mapper.countUnreadByRecipient(workerId));

                // 재요청은 갱신 행이 0이라 최초 읽은 시각이 보존된다.
                assertEquals(0, mapper.markRead(targetId, workerId));
                // 타인 알림은 조건에서 걸러져 갱신되지 않는다.
                assertEquals(0, mapper.markRead(targetId, ownerId));
                assertEquals(0, mapper.existsForRecipient(targetId, ownerId));
                assertEquals(1, mapper.existsForRecipient(targetId, workerId));

                NotificationRow read = mapper.findPageByRecipient(workerId, 20, 0, false).stream()
                        .filter(row -> row.getNotificationId().equals(targetId))
                        .findFirst()
                        .orElseThrow();
                assertTrue(read.getIsRead());
                assertNotNull(read.getReadAt());

                // unreadOnly 는 읽은 알림을 빼고, 개수도 같은 조건으로 센다(SPEC-423-01).
                List<NotificationRow> unreadPage =
                        mapper.findPageByRecipient(workerId, 20, 0, true);
                assertEquals(1, unreadPage.size());
                assertFalse(unreadPage.get(0).getIsRead());
                assertEquals(1, mapper.countByRecipient(workerId, true));

                // 전체 읽음은 남은 안읽음만 갱신한다. 이미 읽은 행은 대상이 아니다.
                assertEquals(1, mapper.markAllRead(workerId));
                assertEquals(0, mapper.countUnreadByRecipient(workerId));
                assertEquals(0, mapper.findPageByRecipient(workerId, 20, 0, true).size());

                // 먼저 읽은 알림의 read_at 이 전체 읽음으로 덮이지 않았다.
                NotificationRow reReadTarget =
                        mapper.findPageByRecipient(workerId, 20, 0, false).stream()
                                .filter(row -> row.getNotificationId().equals(targetId))
                                .findFirst()
                                .orElseThrow();
                assertEquals(read.getReadAt(), reReadTarget.getReadAt());

                // 전체 읽음이 타인 알림을 건드리지 않는다.
                assertEquals(1, mapper.countUnreadByRecipient(ownerId));
            } finally {
                deleteFixtures(jdbcTemplate, workCaseId, workplaceId, workerLoginId, ownerLoginId);
            }
        }
    }

    private void insert(
            NotificationMapper mapper,
            long recipientUserId,
            NotificationType type,
            long sourceId,
            long workCaseId) {
        mapper.insert(NotificationInsert.builder()
                .recipientUserId(recipientUserId)
                .notiType(type.name())
                .sourceType(type.sourceType().name())
                .sourceId(sourceId)
                .workCaseId(workCaseId)
                .title(type.title())
                .content(type.content("주말 홀서빙"))
                .build());
    }

    private long insertUser(
            JdbcTemplate jdbcTemplate,
            String loginId,
            String emailToken,
            String role) {
        jdbcTemplate.update(
                "INSERT INTO users"
                        + " (login_id, email, password_hash, name, role, status)"
                        + " VALUES (?, ?, 'schema-test-hash', '알림 Mapper 테스트', ?, 'ACTIVE')",
                loginId,
                emailToken + "@example.test",
                role
        );
        return jdbcTemplate.queryForObject(
                "SELECT id FROM users WHERE login_id = ?", Long.class, loginId);
    }

    private long insertWorkplace(JdbcTemplate jdbcTemplate, long ownerUserId, String token) {
        String digits = (token.replaceAll("[^0-9]", "") + "0000000000").substring(0, 10);
        jdbcTemplate.update(
                "INSERT INTO workplaces"
                        + " (owner_user_id, business_registration_number, name,"
                        + " representative_name, road_address, phone)"
                        + " VALUES (?, ?, '알림 Mapper 테스트 사업장', '테스트 대표',"
                        + " '서울시 테스트구 테스트로 1', '02-000-0000')",
                ownerUserId,
                digits
        );
        return jdbcTemplate.queryForObject(
                "SELECT id FROM workplaces WHERE business_registration_number = ?",
                Long.class,
                digits
        );
    }

    private long insertWorkCase(
            JdbcTemplate jdbcTemplate,
            long employerId,
            long workerId,
            long workplaceId,
            String token) {
        String title = "[IT-NOTIMAP-" + token.substring(0, 8) + "]";
        jdbcTemplate.update(
                "INSERT INTO work_cases"
                        + " (employer_id, worker_id, workplace_id, title, starts_at, ends_at,"
                        + " workplace_name, workplace_address, agreed_wage, status)"
                        + " VALUES (?, ?, ?, ?, NOW(6) + INTERVAL 1 DAY,"
                        + " NOW(6) + INTERVAL 1 DAY + INTERVAL 8 HOUR,"
                        + " '알림 Mapper 테스트 사업장', '서울시 테스트구', 100000, 'ACCEPTED')",
                employerId,
                workerId,
                workplaceId,
                title
        );
        return jdbcTemplate.queryForObject(
                "SELECT id FROM work_cases WHERE title = ?", Long.class, title);
    }

    private void deleteFixtures(
            JdbcTemplate jdbcTemplate,
            Long workCaseId,
            Long workplaceId,
            String workerLoginId,
            String ownerLoginId) {
        if (workCaseId != null) {
            jdbcTemplate.update("DELETE FROM notifications WHERE work_case_id = ?", workCaseId);
            jdbcTemplate.update("DELETE FROM work_cases WHERE id = ?", workCaseId);
        }
        if (workplaceId != null) {
            jdbcTemplate.update("DELETE FROM workplaces WHERE id = ?", workplaceId);
        }
        jdbcTemplate.update(
                "DELETE FROM users WHERE login_id IN (?, ?)", workerLoginId, ownerLoginId);
    }
}
