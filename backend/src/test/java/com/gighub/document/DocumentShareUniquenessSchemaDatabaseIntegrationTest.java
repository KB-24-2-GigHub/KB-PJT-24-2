package com.gighub.document;

import com.gighub.config.RootConfig;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 보건증 공유의 활성 중복 차단과 철회 뒤 재공유 이력 누적이 현재 스키마에서 보장되는지 확인한다.
 * 공유 대상 결정과 매 요청 유효성 계산은 Service 책임이므로 여기서 검증하지 않는다.
 */
@Tag("database")
class DocumentShareUniquenessSchemaDatabaseIntegrationTest {

    @Test
    @Timeout(60)
    void blocksConcurrentDuplicateActiveShareAndKeepsHistoryAfterRevocation() throws Exception {
        try (AnnotationConfigApplicationContext context =
                     new AnnotationConfigApplicationContext(RootConfig.class)) {
            JdbcTemplate jdbcTemplate =
                    new JdbcTemplate(context.getBean(DataSource.class));
            String token = UUID.randomUUID().toString().replace("-", "");
            String workerLoginId = "it_share_w_" + token;
            String ownerLoginId = "it_share_o_" + token;

            Long documentId = null;
            Long workCaseId = null;
            Long workplaceId = null;

            try {
                long workerId = insertUser(jdbcTemplate, workerLoginId, token + "w", "WORKER");
                long ownerId = insertUser(jdbcTemplate, ownerLoginId, token + "o", "OWNER");
                documentId = insertHealthCertificate(jdbcTemplate, workerId);
                workplaceId = insertWorkplace(jdbcTemplate, ownerId, token);
                workCaseId = insertWorkCase(jdbcTemplate, ownerId, workerId, workplaceId, token);

                int winners = insertActiveShareConcurrently(
                        jdbcTemplate,
                        documentId,
                        workCaseId,
                        ownerId
                );
                assertEquals(1, winners, "동시 공유 생성에서는 한 INSERT만 성공해야 합니다.");
                assertEquals(
                        1,
                        jdbcTemplate.queryForObject(
                                "SELECT COUNT(*) FROM document_shares"
                                        + " WHERE document_id = ? AND active_slot IS NOT NULL",
                                Integer.class,
                                documentId
                        )
                );

                // 철회한 행은 살리지 않고 새 행을 만들어 이력을 누적한다.
                jdbcTemplate.update(
                        "UPDATE document_shares"
                                + " SET status = 'REVOKED', revoked_at = NOW(6)"
                                + " WHERE document_id = ? AND status = 'ACTIVE'",
                        documentId
                );
                insertShare(jdbcTemplate, documentId, workCaseId, ownerId, "ACTIVE");

                assertEquals(
                        2,
                        jdbcTemplate.queryForObject(
                                "SELECT COUNT(*) FROM document_shares WHERE document_id = ?",
                                Integer.class,
                                documentId
                        )
                );
                // 철회 행은 활성 슬롯을 비워 두므로 재공유가 UNIQUE와 충돌하지 않는다.
                assertEquals(
                        1,
                        jdbcTemplate.queryForObject(
                                "SELECT COUNT(*) FROM document_shares"
                                        + " WHERE document_id = ? AND active_slot IS NOT NULL",
                                Integer.class,
                                documentId
                        )
                );
            } finally {
                deleteFixtures(
                        jdbcTemplate,
                        documentId,
                        workCaseId,
                        workplaceId,
                        workerLoginId,
                        ownerLoginId
                );
            }
        }
    }

    private int insertActiveShareConcurrently(
            JdbcTemplate jdbcTemplate,
            long documentId,
            long workCaseId,
            long sharedWithUserId) throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            List<Future<Boolean>> results = List.of(
                    executor.submit(() -> attemptActiveShareInsert(
                            jdbcTemplate,
                            documentId,
                            workCaseId,
                            sharedWithUserId,
                            ready,
                            start
                    )),
                    executor.submit(() -> attemptActiveShareInsert(
                            jdbcTemplate,
                            documentId,
                            workCaseId,
                            sharedWithUserId,
                            ready,
                            start
                    ))
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

    private boolean attemptActiveShareInsert(
            JdbcTemplate jdbcTemplate,
            long documentId,
            long workCaseId,
            long sharedWithUserId,
            CountDownLatch ready,
            CountDownLatch start) throws InterruptedException {
        ready.countDown();
        if (!start.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("동시 공유 생성 시작 신호를 기다리지 못했습니다.");
        }

        try {
            // JdbcTemplate은 호출마다 별도 Connection을 빌리므로 실제 MySQL INSERT가 경합한다.
            insertShare(jdbcTemplate, documentId, workCaseId, sharedWithUserId, "ACTIVE");
            return true;
        } catch (DuplicateKeyException expected) {
            return false;
        }
    }

    private long insertUser(
            JdbcTemplate jdbcTemplate,
            String loginId,
            String emailToken,
            String role) {
        jdbcTemplate.update(
                "INSERT INTO users"
                        + " (login_id, email, password_hash, name, role, status)"
                        + " VALUES (?, ?, 'schema-test-hash', '공유 스키마 테스트', ?, 'ACTIVE')",
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

    private long insertHealthCertificate(JdbcTemplate jdbcTemplate, long ownerUserId) {
        jdbcTemplate.update(
                "INSERT INTO documents"
                        + " (created_by_user_id, owner_user_id, document_type,"
                        + " status, issued_on)"
                        + " VALUES (?, ?, 'HEALTH_CERTIFICATE', 'ACTIVE', CURRENT_DATE)",
                ownerUserId,
                ownerUserId
        );
        return jdbcTemplate.queryForObject(
                "SELECT id FROM documents"
                        + " WHERE owner_user_id = ? AND document_type = 'HEALTH_CERTIFICATE'",
                Long.class,
                ownerUserId
        );
    }

    private long insertWorkplace(
            JdbcTemplate jdbcTemplate,
            long ownerUserId,
            String token) {
        // 사업자번호는 숫자 10자리여야 하므로 Token의 숫자만 사용해 만든다.
        String digits = (token.replaceAll("[^0-9]", "") + "0000000000").substring(0, 10);
        jdbcTemplate.update(
                "INSERT INTO workplaces"
                        + " (owner_user_id, business_registration_number, name,"
                        + " representative_name, road_address, phone)"
                        + " VALUES (?, ?, '공유 스키마 테스트 사업장', '테스트 대표',"
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
        String title = "[IT-SHARE-" + token.substring(0, 8) + "]";
        jdbcTemplate.update(
                "INSERT INTO work_cases"
                        + " (employer_id, worker_id, workplace_id, title, starts_at, ends_at,"
                        + " workplace_name, workplace_address, agreed_wage, status)"
                        + " VALUES (?, ?, ?, ?, NOW(6) + INTERVAL 1 DAY,"
                        + " NOW(6) + INTERVAL 1 DAY + INTERVAL 8 HOUR,"
                        + " '공유 스키마 테스트 사업장', '서울시 테스트구', 100000, 'ACCEPTED')",
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

    private void insertShare(
            JdbcTemplate jdbcTemplate,
            long documentId,
            long workCaseId,
            long sharedWithUserId,
            String status) {
        jdbcTemplate.update(
                "INSERT INTO document_shares"
                        + " (document_id, work_case_id, shared_with_user_id, purpose, status)"
                        + " VALUES (?, ?, ?, 'HEALTH_CERTIFICATE', ?)",
                documentId,
                workCaseId,
                sharedWithUserId,
                status
        );
    }

    private void deleteFixtures(
            JdbcTemplate jdbcTemplate,
            Long documentId,
            Long workCaseId,
            Long workplaceId,
            String workerLoginId,
            String ownerLoginId) {
        if (documentId != null) {
            jdbcTemplate.update(
                    "DELETE FROM document_shares WHERE document_id = ?",
                    documentId
            );
            jdbcTemplate.update("DELETE FROM documents WHERE id = ?", documentId);
        }
        if (workCaseId != null) {
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
