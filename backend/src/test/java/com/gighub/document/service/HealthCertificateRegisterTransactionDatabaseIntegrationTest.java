package com.gighub.document.service;

import com.gighub.config.RootConfig;
import com.gighub.document.storage.DocumentStorageProperties;
import com.gighub.document.validation.ValidatedHealthCertificateFile;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 보건증 등록의 저장소·DB 보상 경계를 실제 Spring Transaction Proxy와 MySQL에서 고정합니다.
 *
 * <p>단위 테스트는 {@code TransactionSynchronizationManager}를 직접 열고 콜백을 손으로 실행하므로
 * "실제 Commit·Rollback이 콜백을 부르는가"를 증명하지 못한다. 여기서는 프록시된
 * {@link HealthCertificateRegisterTransaction} Bean을 바깥 Transaction 안에서 호출해, Commit이면
 * 임시 Object가 남고 Rollback이면 DB 행과 임시 Object가 함께 사라지는지 확인한다.</p>
 */
@Tag("database")
class HealthCertificateRegisterTransactionDatabaseIntegrationTest {

    private static final LocalDate ISSUED_DATE = LocalDate.of(2026, 8, 14);
    private static final byte[] CONTENT = new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0x11};

    @Test
    @Timeout(90)
    void keepsThePendingObjectOnCommitAndCompensatesItOnRollback() throws Exception {
        try (AnnotationConfigApplicationContext context =
                     new AnnotationConfigApplicationContext(RootConfig.class)) {
            JdbcTemplate jdbc = new JdbcTemplate(context.getBean(DataSource.class));
            HealthCertificateRegisterTransaction transaction =
                    context.getBean(HealthCertificateRegisterTransaction.class);
            TransactionTemplate transactionTemplate = new TransactionTemplate(
                    context.getBean(PlatformTransactionManager.class));
            Path basePath = context.getBean(DocumentStorageProperties.class).getBasePath();

            String token = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
            long ownerId = insertUser(jdbc, "hc_tx_" + token);

            HealthCertificateRegistrationHandle committed = null;
            try {
                committed = transactionTemplate.execute(
                        status -> transaction.register(registration(ownerId)));

                // Commit된 등록은 DB 행과 임시 Object를 모두 남긴다(승격은 Commit 뒤 단계다).
                assertEquals(1, countDocuments(jdbc, committed.documentId()));
                assertEquals(1, countVersions(jdbc, committed.documentId()));
                assertTrue(
                        Files.exists(basePath.resolve(committed.pendingStorageKey())),
                        "Commit된 등록의 임시 Object가 남아 있어야 합니다.");

                HealthCertificateRegistrationHandle rolledBack = transactionTemplate.execute(status -> {
                    HealthCertificateRegistrationHandle handle = transaction.register(registration(ownerId));
                    status.setRollbackOnly();
                    return handle;
                });

                // Rollback된 등록은 DB 행도 임시 Object도 남기지 않는다.
                assertEquals(0, countDocuments(jdbc, rolledBack.documentId()));
                assertEquals(0, countVersions(jdbc, rolledBack.documentId()));
                assertFalse(
                        Files.exists(basePath.resolve(rolledBack.pendingStorageKey())),
                        "Rollback된 등록의 임시 Object는 보상 삭제되어야 합니다.");
            } finally {
                if (committed != null) {
                    deleteQuietly(basePath.resolve(committed.pendingStorageKey()));
                    jdbc.update("DELETE FROM document_versions WHERE document_id = ?",
                            committed.documentId());
                    jdbc.update("DELETE FROM documents WHERE id = ?", committed.documentId());
                }
                jdbc.update("DELETE FROM users WHERE id = ?", ownerId);
            }
        }
    }

    private ValidatedHealthCertificateRegistration registration(long ownerId) {
        return new ValidatedHealthCertificateRegistration(
                ownerId, ISSUED_DATE,
                new ValidatedHealthCertificateFile(CONTENT, "jpg", "image/jpeg", sha256(CONTENT)));
    }

    private int countDocuments(JdbcTemplate jdbc, long documentId) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM documents WHERE id = ?", Integer.class, documentId);
    }

    private int countVersions(JdbcTemplate jdbc, long documentId) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM document_versions WHERE document_id = ?",
                Integer.class, documentId);
    }

    private long insertUser(JdbcTemplate jdbc, String loginId) {
        jdbc.update(
                "INSERT INTO users (login_id, email, password_hash, name, role, status)"
                        + " VALUES (?, ?, 'test-hash', '박근로', 'WORKER', 'ACTIVE')",
                loginId, loginId + "@example.test");
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // 정리 실패는 검증 결과를 바꾸지 않는다.
        }
    }

    private byte[] sha256(byte[] content) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(content);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
