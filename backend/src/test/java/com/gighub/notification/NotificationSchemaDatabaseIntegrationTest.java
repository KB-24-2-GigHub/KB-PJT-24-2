package com.gighub.notification;

import com.gighub.config.RootConfig;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 알림 저장 불변식(SPEC-383-01)이 현재 Schema에서 실제로 강제되는지 확인한다.
 *
 * <p>중복 방지는 애플리케이션 선검사가 아니라 Database 유일 제약이 막아야 하므로 동시 INSERT로
 * 검증한다. 같은 근무의 보건증 재공유와 후속 분쟁은 서로 다른 이벤트이므로 막히면 안 되고,
 * 그 과잉 제약 회귀도 함께 고정한다. 알림 문구 생성과 수신자 판정은 Service 책임이라 다루지
 * 않는다.</p>
 */
@Tag("database")
class NotificationSchemaDatabaseIntegrationTest {

    @Test
    @Timeout(60)
    void enforcesNotificationPersistenceInvariants() throws Exception {
        try (AnnotationConfigApplicationContext context =
                     new AnnotationConfigApplicationContext(RootConfig.class)) {
            JdbcTemplate jdbcTemplate = new JdbcTemplate(context.getBean(DataSource.class));
            String token = UUID.randomUUID().toString().replace("-", "");
            String workerLoginId = "it_noti_w_" + token;
            String ownerLoginId = "it_noti_o_" + token;

            Long workCaseId = null;
            Long workplaceId = null;

            try {
                long workerId = insertUser(jdbcTemplate, workerLoginId, token + "w", "WORKER");
                long ownerId = insertUser(jdbcTemplate, ownerLoginId, token + "o", "OWNER");
                workplaceId = insertWorkplace(jdbcTemplate, ownerId, token);
                workCaseId = insertWorkCase(jdbcTemplate, ownerId, workerId, workplaceId, token);

                assertBlocksDuplicateEvent(jdbcTemplate, workerId, workCaseId);
                assertAllowsDistinctEventsOnSameWorkCase(jdbcTemplate, workerId, workCaseId);
                assertSeparatesRecipients(jdbcTemplate, workerId, ownerId, workCaseId);
                assertEnforcesTypePairing(jdbcTemplate, workerId, workCaseId);
                assertEnforcesReadStateConsistency(jdbcTemplate, workerId, workCaseId);
                assertEnforcesWorkCaseReference(jdbcTemplate, workerId);
            } finally {
                deleteFixtures(jdbcTemplate, workCaseId, workplaceId, workerLoginId, ownerLoginId);
            }
        }
    }

    /** 같은 이벤트를 동시에 두 번 적재해도 한 건만 남는다. */
    private void assertBlocksDuplicateEvent(
            JdbcTemplate jdbcTemplate,
            long recipientUserId,
            long workCaseId) throws Exception {
        int winners = insertConcurrently(jdbcTemplate, recipientUserId, workCaseId);

        assertEquals(1, winners, "같은 이벤트의 동시 적재에서는 한 INSERT만 성공해야 합니다.");
        assertEquals(
                1,
                countByType(jdbcTemplate, recipientUserId, "WORK_CASE_CONFIRMED"),
                "동시 적재 뒤에도 알림은 한 건이어야 합니다."
        );
    }

    /**
     * 같은 근무의 서로 다른 이벤트는 막히지 않는다.
     *
     * <p>이벤트 식별자를 근무 ID로 고정하면 보건증 재공유와 종료된 분쟁 뒤의 새 분쟁이 중복으로
     * 오인돼 영구 누락된다. 그 과잉 제약이 다시 들어오지 못하게 고정한다.</p>
     */
    private void assertAllowsDistinctEventsOnSameWorkCase(
            JdbcTemplate jdbcTemplate,
            long recipientUserId,
            long workCaseId) {
        insert(jdbcTemplate, recipientUserId, "DOC_SHARED", "DOCUMENT_SHARE", 4001L, workCaseId);
        insert(jdbcTemplate, recipientUserId, "DOC_SHARED", "DOCUMENT_SHARE", 4002L, workCaseId);
        assertEquals(
                2,
                countByType(jdbcTemplate, recipientUserId, "DOC_SHARED"),
                "보건증을 다시 공유하면 두 번째 알림도 생성돼야 합니다."
        );

        insert(jdbcTemplate, recipientUserId, "WAGE_REPORTED", "DISPUTE", 5001L, workCaseId);
        insert(jdbcTemplate, recipientUserId, "WAGE_REPORTED", "DISPUTE", 5002L, workCaseId);
        assertEquals(
                2,
                countByType(jdbcTemplate, recipientUserId, "WAGE_REPORTED"),
                "분쟁이 종료된 뒤 새 분쟁의 알림도 생성돼야 합니다."
        );

        // SETTLED와 REFUNDED는 같은 정산 행을 가리키지만 notiType이 달라 서로를 막지 않는다.
        insert(jdbcTemplate, recipientUserId, "SETTLED", "SETTLEMENT", 6001L, workCaseId);
        insert(jdbcTemplate, recipientUserId, "REFUNDED", "SETTLEMENT", 6001L, workCaseId);
        assertEquals(1, countByType(jdbcTemplate, recipientUserId, "SETTLED"));
        assertEquals(1, countByType(jdbcTemplate, recipientUserId, "REFUNDED"));
    }

    /** 수신자가 둘인 유형은 수신자마다 별도 행을 갖는다. */
    private void assertSeparatesRecipients(
            JdbcTemplate jdbcTemplate,
            long workerId,
            long ownerId,
            long workCaseId) {
        insert(jdbcTemplate, ownerId, "WORK_CASE_CONFIRMED", "WORK_CASE", workCaseId, workCaseId);

        assertEquals(1, countByType(jdbcTemplate, workerId, "WORK_CASE_CONFIRMED"));
        assertEquals(1, countByType(jdbcTemplate, ownerId, "WORK_CASE_CONFIRMED"));
    }

    /** 유형과 이벤트 유형의 짝은 SPEC-382-01의 표대로만 저장할 수 있다. */
    private void assertEnforcesTypePairing(
            JdbcTemplate jdbcTemplate,
            long recipientUserId,
            long workCaseId) {
        assertThrows(
                DataAccessException.class,
                () -> insert(
                        jdbcTemplate, recipientUserId, "DOC_SHARED", "DISPUTE", 7001L, workCaseId),
                "이벤트 표에 없는 notiType·sourceType 짝은 거부돼야 합니다."
        );
        assertThrows(
                DataAccessException.class,
                () -> insert(
                        jdbcTemplate,
                        recipientUserId,
                        "NOT_A_NOTI_TYPE",
                        "WORK_CASE",
                        workCaseId,
                        workCaseId),
                "승인된 6종 밖 notiType은 거부돼야 합니다."
        );
    }

    /** 읽음 여부와 읽음 시각은 한쪽만 채울 수 없다. */
    private void assertEnforcesReadStateConsistency(
            JdbcTemplate jdbcTemplate,
            long recipientUserId,
            long workCaseId) {
        assertThrows(
                DataAccessException.class,
                () -> jdbcTemplate.update(
                        "INSERT INTO notifications"
                                + " (recipient_user_id, noti_type, source_type, source_id,"
                                + " work_case_id, title, content, is_read, read_at)"
                                + " VALUES (?, 'ESCROW_HELD', 'ESCROW', 8001, ?,"
                                + " '예치 완료', '임금이 예치됐습니다.', 1, NULL)",
                        recipientUserId,
                        workCaseId
                ),
                "읽음 표시에 읽음 시각이 없으면 거부돼야 합니다."
        );

        insert(jdbcTemplate, recipientUserId, "ESCROW_HELD", "ESCROW", 8002L, workCaseId);
        assertThrows(
                DataAccessException.class,
                () -> jdbcTemplate.update(
                        "UPDATE notifications SET is_read = 1"
                                + " WHERE recipient_user_id = ? AND source_id = 8002",
                        recipientUserId
                ),
                "읽음 시각 없이 읽음으로만 갱신하면 거부돼야 합니다."
        );
    }

    /** 이동 대상은 실재하는 근무만 가리킬 수 있다. */
    private void assertEnforcesWorkCaseReference(JdbcTemplate jdbcTemplate, long recipientUserId) {
        long missingWorkCaseId = jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(id), 0) + 1000 FROM work_cases", Long.class);

        assertThrows(
                DataAccessException.class,
                () -> insert(
                        jdbcTemplate,
                        recipientUserId,
                        "SETTLED",
                        "SETTLEMENT",
                        9001L,
                        missingWorkCaseId),
                "존재하지 않는 근무를 가리키는 알림은 거부돼야 합니다."
        );
    }

    private int insertConcurrently(
            JdbcTemplate jdbcTemplate,
            long recipientUserId,
            long workCaseId) throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            List<Future<Boolean>> results = List.of(
                    executor.submit(() -> attemptInsert(
                            jdbcTemplate, recipientUserId, workCaseId, ready, start)),
                    executor.submit(() -> attemptInsert(
                            jdbcTemplate, recipientUserId, workCaseId, ready, start))
            );

            assertTrue(ready.await(5, TimeUnit.SECONDS), "두 INSERT가 시작 준비를 마쳐야 합니다.");
            start.countDown();

            int winners = 0;
            for (Future<Boolean> result : results) {
                if (Boolean.TRUE.equals(result.get(30, TimeUnit.SECONDS))) {
                    winners++;
                }
            }
            return winners;
        } finally {
            // 준비 단계에서 실패해도 대기 중인 스레드가 남지 않게 시작 신호를 열어 둔다.
            start.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    private boolean attemptInsert(
            JdbcTemplate jdbcTemplate,
            long recipientUserId,
            long workCaseId,
            CountDownLatch ready,
            CountDownLatch start) throws InterruptedException {
        ready.countDown();
        if (!start.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("동시 적재 시작 신호를 기다리지 못했습니다.");
        }

        try {
            // JdbcTemplate은 호출마다 별도 Connection을 빌리므로 실제 MySQL INSERT가 경합한다.
            insert(
                    jdbcTemplate,
                    recipientUserId,
                    "WORK_CASE_CONFIRMED",
                    "WORK_CASE",
                    workCaseId,
                    workCaseId
            );
            return true;
        } catch (DuplicateKeyException expected) {
            return false;
        }
    }

    private void insert(
            JdbcTemplate jdbcTemplate,
            long recipientUserId,
            String notiType,
            String sourceType,
            long sourceId,
            long workCaseId) {
        jdbcTemplate.update(
                "INSERT INTO notifications"
                        + " (recipient_user_id, noti_type, source_type, source_id,"
                        + " work_case_id, title, content)"
                        + " VALUES (?, ?, ?, ?, ?, '알림 스키마 테스트', '알림 스키마 테스트 본문')",
                recipientUserId,
                notiType,
                sourceType,
                sourceId,
                workCaseId
        );
    }

    private int countByType(JdbcTemplate jdbcTemplate, long recipientUserId, String notiType) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM notifications"
                        + " WHERE recipient_user_id = ? AND noti_type = ?",
                Integer.class,
                recipientUserId,
                notiType
        );
    }

    private long insertUser(
            JdbcTemplate jdbcTemplate,
            String loginId,
            String emailToken,
            String role) {
        jdbcTemplate.update(
                "INSERT INTO users"
                        + " (login_id, email, password_hash, name, role, status)"
                        + " VALUES (?, ?, 'schema-test-hash', '알림 스키마 테스트', ?, 'ACTIVE')",
                loginId,
                emailToken + "@example.test",
                role
        );
        return jdbcTemplate.queryForObject(
                "SELECT id FROM users WHERE login_id = ?",
                Long.class,
                loginId
        );
    }

    private long insertWorkplace(JdbcTemplate jdbcTemplate, long ownerUserId, String token) {
        // 사업자번호는 숫자 10자리여야 하므로 Token의 숫자만 사용해 만든다.
        String digits = (token.replaceAll("[^0-9]", "") + "0000000000").substring(0, 10);
        jdbcTemplate.update(
                "INSERT INTO workplaces"
                        + " (owner_user_id, business_registration_number, name,"
                        + " representative_name, road_address, phone)"
                        + " VALUES (?, ?, '알림 스키마 테스트 사업장', '테스트 대표',"
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
        String title = "[IT-NOTI-" + token.substring(0, 8) + "]";
        jdbcTemplate.update(
                "INSERT INTO work_cases"
                        + " (employer_id, worker_id, workplace_id, title, starts_at, ends_at,"
                        + " workplace_name, workplace_address, agreed_wage, status)"
                        + " VALUES (?, ?, ?, ?, NOW(6) + INTERVAL 1 DAY,"
                        + " NOW(6) + INTERVAL 1 DAY + INTERVAL 8 HOUR,"
                        + " '알림 스키마 테스트 사업장', '서울시 테스트구', 100000, 'ACCEPTED')",
                employerId,
                workerId,
                workplaceId,
                title
        );
        return jdbcTemplate.queryForObject(
                "SELECT id FROM work_cases WHERE title = ?",
                Long.class,
                title
        );
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
                "DELETE FROM users WHERE login_id IN (?, ?)",
                workerLoginId,
                ownerLoginId
        );
    }
}
