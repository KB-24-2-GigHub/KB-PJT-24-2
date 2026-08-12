package com.gighub.document;

import com.gighub.config.RootConfig;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.util.Arrays;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@Tag("database")
class DocumentAccessAuditSchemaDatabaseIntegrationTest {

    private static final String JDBC_URL_PROPERTY = "database.jdbc-url";
    private static final String TEST_JDBC_URL_ENV =
            "GIGHUB_TEST_DATABASE_JDBC_URL";

    @Test
    void preservesLegacyRowsAndConstrainsNewAuditDetails() {
        String previousJdbcUrl = System.getProperty(JDBC_URL_PROPERTY);
        String testJdbcUrl = System.getenv(TEST_JDBC_URL_ENV);

        // 실제 로컬 DB 설정의 비밀번호는 그대로 두고 검증용 스키마 URL만 실행 시점에 덮어쓴다.
        if (testJdbcUrl != null && !testJdbcUrl.isBlank()) {
            System.setProperty(JDBC_URL_PROPERTY, testJdbcUrl);
        }

        try (AnnotationConfigApplicationContext context =
                     new AnnotationConfigApplicationContext(RootConfig.class)) {
            JdbcTemplate jdbcTemplate =
                    new JdbcTemplate(context.getBean(DataSource.class));
            String token = UUID.randomUUID().toString().replace("-", "");
            String firstLoginId = "it_doc_audit_a_" + token;
            String secondLoginId = "it_doc_audit_b_" + token;

            Long firstDocumentId = null;
            Long secondDocumentId = null;

            try {
                long firstUserId = insertUser(
                        jdbcTemplate,
                        firstLoginId,
                        token + "a"
                );
                long secondUserId = insertUser(
                        jdbcTemplate,
                        secondLoginId,
                        token + "b"
                );
                firstDocumentId = insertDocument(jdbcTemplate, firstUserId);
                secondDocumentId = insertDocument(jdbcTemplate, secondUserId);
                long firstVersionId = insertVersion(
                        jdbcTemplate,
                        firstDocumentId,
                        token + "-first"
                );
                long secondVersionId = insertVersion(
                        jdbcTemplate,
                        secondDocumentId,
                        token + "-second"
                );

                insertAudit(
                        jdbcTemplate,
                        firstDocumentId,
                        firstVersionId,
                        firstUserId,
                        "HEALTH_CERT_FILE_DOWNLOAD",
                        "ALLOWED",
                        null
                );
                insertAudit(
                        jdbcTemplate,
                        firstDocumentId,
                        firstVersionId,
                        secondUserId,
                        "HEALTH_CERT_FILE_VIEW",
                        "DENIED",
                        "PARTY_ACCESS_DENIED"
                );

                // 기존 감사 행은 복원할 수 없는 상세값을 NULL로 유지한 채 업그레이드되어야 한다.
                insertAudit(
                        jdbcTemplate,
                        firstDocumentId,
                        null,
                        firstUserId,
                        "DOCUMENT_DETAIL_VIEW",
                        "ALLOWED",
                        null
                );

                assertEquals(
                        3,
                        jdbcTemplate.queryForObject(
                                "SELECT COUNT(*) FROM document_access_logs"
                                        + " WHERE document_id = ?",
                                Integer.class,
                                firstDocumentId
                        )
                );

                Long testedFirstDocumentId = firstDocumentId;
                assertThrows(
                        DataAccessException.class,
                        () -> insertAudit(
                                jdbcTemplate,
                                testedFirstDocumentId,
                                secondVersionId,
                                firstUserId,
                                "HEALTH_CERT_FILE_VIEW",
                                "ALLOWED",
                                null
                        )
                );
                assertThrows(
                        DataAccessException.class,
                        () -> insertAudit(
                                jdbcTemplate,
                                testedFirstDocumentId,
                                firstVersionId,
                                firstUserId,
                                "HEALTH_CERT_FILE_VIEW",
                                "ALLOWED",
                                "PARTY_ACCESS_DENIED"
                        )
                );
                assertThrows(
                        DataAccessException.class,
                        () -> insertAudit(
                                jdbcTemplate,
                                testedFirstDocumentId,
                                firstVersionId,
                                secondUserId,
                                "HEALTH_CERT_FILE_VIEW",
                                "DENIED",
                                ""
                        )
                );

                // 승인 목록 밖의 action과 거부 사유는 저장할 수 없어야 한다.
                assertThrows(
                        DataAccessException.class,
                        () -> insertAudit(
                                jdbcTemplate,
                                testedFirstDocumentId,
                                firstVersionId,
                                firstUserId,
                                "IT_UNLISTED_ACTION",
                                "ALLOWED",
                                null
                        )
                );
                assertThrows(
                        DataAccessException.class,
                        () -> insertAudit(
                                jdbcTemplate,
                                testedFirstDocumentId,
                                firstVersionId,
                                secondUserId,
                                "HEALTH_CERT_FILE_VIEW",
                                "DENIED",
                                "IT_UNLISTED_REASON"
                        )
                );
            } finally {
                deleteFixtures(
                        jdbcTemplate,
                        firstDocumentId,
                        secondDocumentId,
                        firstLoginId,
                        secondLoginId
                );
            }
        } finally {
            restoreJdbcUrl(previousJdbcUrl);
        }
    }

    private void restoreJdbcUrl(String previousJdbcUrl) {
        if (previousJdbcUrl == null) {
            System.clearProperty(JDBC_URL_PROPERTY);
            return;
        }
        System.setProperty(JDBC_URL_PROPERTY, previousJdbcUrl);
    }

    private long insertUser(
            JdbcTemplate jdbcTemplate,
            String loginId,
            String emailToken) {
        jdbcTemplate.update(
                "INSERT INTO users"
                        + " (login_id, email, password_hash, name, role, status)"
                        + " VALUES (?, ?, 'schema-test-hash', '문서 감사 스키마 테스트',"
                        + " 'WORKER', 'ACTIVE')",
                loginId,
                emailToken + "@example.test"
        );
        return jdbcTemplate.queryForObject(
                "SELECT id FROM users WHERE login_id = ?",
                Long.class,
                loginId
        );
    }

    private long insertDocument(JdbcTemplate jdbcTemplate, long ownerUserId) {
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
                        + " WHERE owner_user_id = ?"
                        + " AND document_type = 'HEALTH_CERTIFICATE'",
                Long.class,
                ownerUserId
        );
    }

    private long insertVersion(
            JdbcTemplate jdbcTemplate,
            long documentId,
            String storageToken) {
        byte[] checksum = new byte[32];
        Arrays.fill(checksum, (byte) 1);
        jdbcTemplate.update(
                "INSERT INTO document_versions"
                        + " (document_id, version_no, version_type, storage_key,"
                        + " mime_type, size_bytes, checksum)"
                        + " VALUES (?, 1, 'ORIGINAL', ?, 'application/pdf', 1, ?)",
                documentId,
                "schema-tests/document-audit/" + storageToken + ".pdf",
                checksum
        );
        return jdbcTemplate.queryForObject(
                "SELECT id FROM document_versions"
                        + " WHERE document_id = ? AND version_no = 1",
                Long.class,
                documentId
        );
    }

    private void insertAudit(
            JdbcTemplate jdbcTemplate,
            long documentId,
            Long documentVersionId,
            long actorUserId,
            String action,
            String result,
            String denialReason) {
        jdbcTemplate.update(
                "INSERT INTO document_access_logs"
                        + " (document_id, document_version_id, actor_user_id,"
                        + " action, result, denial_reason)"
                        + " VALUES (?, ?, ?, ?, ?, ?)",
                documentId,
                documentVersionId,
                actorUserId,
                action,
                result,
                denialReason
        );
    }

    private void deleteFixtures(
            JdbcTemplate jdbcTemplate,
            Long firstDocumentId,
            Long secondDocumentId,
            String firstLoginId,
            String secondLoginId) {
        if (firstDocumentId != null || secondDocumentId != null) {
            jdbcTemplate.update(
                    "DELETE FROM document_access_logs"
                            + " WHERE document_id IN (?, ?)",
                    firstDocumentId,
                    secondDocumentId
            );
            jdbcTemplate.update(
                    "DELETE FROM document_versions"
                            + " WHERE document_id IN (?, ?)",
                    firstDocumentId,
                    secondDocumentId
            );
            jdbcTemplate.update(
                    "DELETE FROM documents WHERE id IN (?, ?)",
                    firstDocumentId,
                    secondDocumentId
            );
        }
        jdbcTemplate.update(
                "DELETE FROM users WHERE login_id IN (?, ?)",
                firstLoginId,
                secondLoginId
        );
    }
}
