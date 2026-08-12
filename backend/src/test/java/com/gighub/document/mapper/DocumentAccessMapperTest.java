package com.gighub.document.mapper;

import com.gighub.config.RootConfig;
import com.gighub.document.dto.DocumentDetailResponse;
import com.gighub.document.exception.DocumentNotFoundException;
import com.gighub.document.mapper.param.DocumentAccessLogParam;
import com.gighub.document.mapper.result.DocumentFileAccessRow;
import com.gighub.document.mapper.result.DocumentHealthShareAccessRow;
import com.gighub.document.service.DocumentFileAccessService;
import com.gighub.document.service.DocumentFileAccessTransaction;
import com.gighub.document.service.DocumentFileResult;
import com.gighub.document.service.DocumentDetailAccessTransaction;
import com.gighub.document.storage.DocumentStorageAdapter;
import com.gighub.document.storage.Sha256;
import com.gighub.member.domain.UserRole;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 파일 접근 Projection, 감사 저장, 동시 철회 잠금을 실제 MySQL에서 검증합니다. */
@Tag("database")
class DocumentAccessMapperTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 11, 12, 0);
    private static final LocalDate TODAY = NOW.toLocalDate();
    private static final byte[] FILE_CONTENT = new byte[]{1, 2, 3};

    @Test
    void mapsAllowedVersionsAndValidHealthShare() {
        try (AnnotationConfigApplicationContext context =
                     new AnnotationConfigApplicationContext(RootConfig.class)) {
            JdbcTemplate jdbc = new JdbcTemplate(context.getBean(DataSource.class));
            DocumentAccessMapper mapper = context.getBean(DocumentAccessMapper.class);
            TransactionTemplate tx = new TransactionTemplate(
                    context.getBean(PlatformTransactionManager.class));
            Fixture fixture = insertFixture(jdbc);

            try {
                // 과거 호환용 만료 시각은 접근 기준이 아니며, 현재 공유와 업무 상태로 다시 판단한다.
                jdbc.update(
                        "UPDATE document_shares SET expires_at = ? WHERE id = ?",
                        NOW.minusDays(1),
                        fixture.shareId);

                DocumentFileAccessRow contract = tx.execute(status ->
                        mapper.lockFileAccessContext(fixture.contractDocumentId));
                assertNotNull(contract);
                assertEquals(fixture.contractVersionId, contract.getVersionId());
                assertEquals("SIGNED", contract.getVersionType());
                assertEquals(fixture.ownerId, contract.getContractOwnerUserId());
                assertEquals(fixture.workerId, contract.getContractWorkerUserId());
                assertEquals(1L, contract.getSizeBytes());
                assertNotNull(contract.getVersionCreatedAt());
                assertNotNull(contract.getDocumentCreatedAt());
                assertArrayEquals(fixture.checksum, contract.getChecksum());

                DocumentFileAccessRow health = tx.execute(status ->
                        mapper.lockFileAccessContext(fixture.healthDocumentId));
                assertNotNull(health);
                assertEquals(fixture.healthVersionId, health.getVersionId());
                assertEquals("ORIGINAL", health.getVersionType());

                Long shareId = tx.execute(status -> mapper.lockValidHealthShare(
                        fixture.healthDocumentId,
                        fixture.ownerId,
                        NOW,
                        TODAY));
                assertEquals(fixture.shareId, shareId);
                assertTrue(mapper.hasHealthShareHistory(
                        fixture.healthDocumentId, fixture.ownerId));
                List<DocumentHealthShareAccessRow> detailContexts = tx.execute(status ->
                        mapper.lockValidHealthShareContexts(
                                fixture.healthDocumentId,
                                fixture.ownerId,
                                fixture.workCaseId,
                                NOW,
                                TODAY));
                assertNotNull(detailContexts);
                assertEquals(1, detailContexts.size());
                assertEquals(fixture.shareId, detailContexts.get(0).getShareId());
                assertEquals(fixture.workCaseId, detailContexts.get(0).getWorkCaseId());
                assertTrue(mapper.hasHealthShareHistoryForWorkCase(
                        fixture.healthDocumentId,
                        fixture.ownerId,
                        fixture.workCaseId));
                assertTrue(tx.execute(status -> mapper.lockValidHealthShareContexts(
                        fixture.healthDocumentId,
                        fixture.ownerId,
                        fixture.workCaseId + 1,
                        NOW,
                        TODAY)).isEmpty());
                assertFalse(mapper.hasHealthShareHistoryForWorkCase(
                        fixture.healthDocumentId,
                        fixture.ownerId,
                        fixture.workCaseId + 1));

                assertEquals(1, mapper.insertAccessLog(DocumentAccessLogParam.builder()
                        .documentId(fixture.healthDocumentId)
                        .documentVersionId(fixture.healthVersionId)
                        .actorUserId(fixture.ownerId)
                        .action("HEALTH_CERT_FILE_VIEW")
                        .result("ALLOWED")
                        .build()));
                assertEquals(1, jdbc.queryForObject(
                        "SELECT COUNT(*) FROM document_access_logs WHERE document_id = ?",
                        Integer.class,
                        fixture.healthDocumentId));
            } finally {
                deleteFixture(jdbc, fixture);
            }
        }
    }

    @Test
    void commitsSelectedDetailAllowedAndRevokedDeniedAudits() {
        try (AnnotationConfigApplicationContext context =
                     new AnnotationConfigApplicationContext(RootConfig.class)) {
            JdbcTemplate jdbc = new JdbcTemplate(context.getBean(DataSource.class));
            DocumentDetailAccessTransaction detailAccess =
                    context.getBean(DocumentDetailAccessTransaction.class);
            Fixture fixture = insertFixture(jdbc);

            try {
                DocumentDetailResponse detail = detailAccess.loadDetail(
                        fixture.healthDocumentId,
                        fixture.ownerId,
                        UserRole.OWNER,
                        fixture.workCaseId);
                assertEquals(fixture.workCaseId, detail.getItem().getWorkCaseId());
                assertEquals(1, jdbc.queryForObject(
                        "SELECT COUNT(*) FROM document_access_logs"
                                + " WHERE document_id = ? AND actor_user_id = ?"
                                + " AND action = 'DOCUMENT_DETAIL_VIEW'"
                                + " AND result = 'ALLOWED' AND denial_reason IS NULL",
                        Integer.class,
                        fixture.healthDocumentId,
                        fixture.ownerId));

                jdbc.update(
                        "UPDATE document_shares"
                                + " SET status = 'REVOKED', revoked_at = CURRENT_TIMESTAMP(6)"
                                + " WHERE id = ?",
                        fixture.shareId);
                assertThrows(DocumentNotFoundException.class,
                        () -> detailAccess.loadDetail(
                                fixture.healthDocumentId,
                                fixture.ownerId,
                                UserRole.OWNER,
                                fixture.workCaseId));
                assertEquals(1, jdbc.queryForObject(
                        "SELECT COUNT(*) FROM document_access_logs"
                                + " WHERE document_id = ? AND actor_user_id = ?"
                                + " AND action = 'DOCUMENT_DETAIL_VIEW'"
                                + " AND result = 'DENIED'"
                                + " AND denial_reason = 'DOCUMENT_UNAVAILABLE'",
                        Integer.class,
                        fixture.healthDocumentId,
                        fixture.ownerId));
            } finally {
                deleteFixture(jdbc, fixture);
            }
        }
    }

    @Test
    void rejectsMalformedHealthShareRelationships() {
        try (AnnotationConfigApplicationContext context =
                     new AnnotationConfigApplicationContext(RootConfig.class)) {
            JdbcTemplate jdbc = new JdbcTemplate(context.getBean(DataSource.class));
            DocumentAccessMapper mapper = context.getBean(DocumentAccessMapper.class);
            TransactionTemplate tx = new TransactionTemplate(
                    context.getBean(PlatformTransactionManager.class));
            Fixture fixture = insertFixture(jdbc);
            String token = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
            long otherOwnerId = insertUser(
                    jdbc, "da_other_owner_" + token, "OWNER", "박사장");

            try {
                jdbc.update(
                        "UPDATE document_shares SET shared_with_user_id = ? WHERE id = ?",
                        otherOwnerId,
                        fixture.shareId);
                Long mismatchedRecipient = tx.execute(status -> mapper.lockValidHealthShare(
                        fixture.healthDocumentId,
                        otherOwnerId,
                        NOW,
                        TODAY));
                assertNull(mismatchedRecipient);

                jdbc.update(
                        "UPDATE document_shares SET shared_with_user_id = ? WHERE id = ?",
                        fixture.ownerId,
                        fixture.shareId);
                jdbc.update(
                        "UPDATE documents SET owner_user_id = ? WHERE id = ?",
                        otherOwnerId,
                        fixture.healthDocumentId);
                Long mismatchedDocumentOwner =
                        tx.execute(status -> mapper.lockValidHealthShare(
                                fixture.healthDocumentId,
                                fixture.ownerId,
                                NOW,
                                TODAY));
                assertNull(mismatchedDocumentOwner);
            } finally {
                deleteFixture(jdbc, fixture);
                jdbc.update("DELETE FROM users WHERE id = ?", otherOwnerId);
            }
        }
    }

    @Test
    void validShareLockSerializesConcurrentRevocation() throws Exception {
        try (AnnotationConfigApplicationContext context =
                     new AnnotationConfigApplicationContext(RootConfig.class)) {
            JdbcTemplate jdbc = new JdbcTemplate(context.getBean(DataSource.class));
            DocumentAccessMapper mapper = context.getBean(DocumentAccessMapper.class);
            PlatformTransactionManager manager = context.getBean(PlatformTransactionManager.class);
            Fixture fixture = insertFixture(jdbc);
            ExecutorService executor = Executors.newFixedThreadPool(2);
            CountDownLatch locked = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            CountDownLatch revokeStarted = new CountDownLatch(1);

            try {
                Future<Long> reader = executor.submit(() ->
                        new TransactionTemplate(manager).execute(status -> {
                            mapper.lockFileAccessContext(fixture.healthDocumentId);
                            Long shareId = mapper.lockValidHealthShare(
                                    fixture.healthDocumentId,
                                    fixture.ownerId,
                                    NOW,
                                    TODAY);
                            locked.countDown();
                            try {
                                if (!release.await(5, TimeUnit.SECONDS)) {
                                    throw new IllegalStateException("동시성 테스트 잠금 해제 시간 초과");
                                }
                            } catch (InterruptedException exception) {
                                Thread.currentThread().interrupt();
                                throw new IllegalStateException(exception);
                            }
                            return shareId;
                        }));
                assertTrue(locked.await(5, TimeUnit.SECONDS));

                Future<Integer> revoke = executor.submit(() -> {
                    revokeStarted.countDown();
                    return new TransactionTemplate(manager).execute(status -> jdbc.update(
                            "UPDATE document_shares"
                                    + " SET status = 'REVOKED', revoked_at = ?"
                                    + " WHERE id = ? AND status = 'ACTIVE'",
                            NOW,
                            fixture.shareId));
                });
                assertTrue(revokeStarted.await(5, TimeUnit.SECONDS));

                // 파일 접근 트랜잭션이 공유 행 잠금을 가진 동안 철회 commit은 완료될 수 없습니다.
                assertThrows(TimeoutException.class,
                        () -> revoke.get(250, TimeUnit.MILLISECONDS));
                release.countDown();

                assertEquals(fixture.shareId, reader.get(5, TimeUnit.SECONDS));
                assertEquals(1, revoke.get(5, TimeUnit.SECONDS));
                Long validAfterRevocation = new TransactionTemplate(manager).execute(status ->
                        mapper.lockValidHealthShare(
                                fixture.healthDocumentId,
                                fixture.ownerId,
                                NOW.plusSeconds(1),
                                TODAY));
                assertNull(validAfterRevocation);
                assertTrue(mapper.hasHealthShareHistory(
                        fixture.healthDocumentId, fixture.ownerId));
            } finally {
                release.countDown();
                executor.shutdownNow();
                deleteFixture(jdbc, fixture);
            }
        }
    }

    @Test
    void revocationCommitsWhileStorageReadIsBlocked() throws Exception {
        try (AnnotationConfigApplicationContext context =
                     new AnnotationConfigApplicationContext(RootConfig.class)) {
            JdbcTemplate jdbc = new JdbcTemplate(context.getBean(DataSource.class));
            PlatformTransactionManager manager = context.getBean(PlatformTransactionManager.class);
            DocumentFileAccessTransaction accessTransaction =
                    context.getBean(DocumentFileAccessTransaction.class);
            Fixture fixture = insertFixture(jdbc);
            BlockingDocumentStorageAdapter storage = new BlockingDocumentStorageAdapter();
            DocumentFileAccessService service =
                    new DocumentFileAccessService(accessTransaction, storage);
            ExecutorService executor = Executors.newFixedThreadPool(2);

            try {
                // 시스템 시계가 바뀌어도 이 경합 테스트의 공유가 유효하도록 먼 미래로 고정합니다.
                jdbc.update("UPDATE work_cases SET ends_at = '2099-08-20 18:00:00' WHERE id = ?",
                        fixture.workCaseId);
                jdbc.update("UPDATE documents SET expires_on = '2099-08-11' WHERE id = ?",
                        fixture.healthDocumentId);
                jdbc.update(
                        "UPDATE document_versions SET size_bytes = ?, checksum = ? WHERE id = ?",
                        FILE_CONTENT.length,
                        Sha256.digest(FILE_CONTENT),
                        fixture.healthVersionId);

                Future<DocumentFileResult> reader = executor.submit(() -> service.loadFile(
                        fixture.healthDocumentId,
                        fixture.ownerId,
                        UserRole.OWNER,
                        "view"));
                assertTrue(storage.awaitReadStarted());

                Future<Integer> revoke = executor.submit(() ->
                        new TransactionTemplate(manager).execute(status -> jdbc.update(
                                "UPDATE document_shares"
                                        + " SET status = 'REVOKED', revoked_at = CURRENT_TIMESTAMP(6)"
                                        + " WHERE id = ? AND status = 'ACTIVE'",
                                fixture.shareId)));

                // 저장소 읽기가 멈춘 동안에도 prepare Transaction의 잠금은 이미 풀려 있어야 합니다.
                assertEquals(1, revoke.get(5, TimeUnit.SECONDS));
                storage.releaseRead();

                ExecutionException failure = assertThrows(
                        ExecutionException.class,
                        () -> reader.get(5, TimeUnit.SECONDS));
                assertTrue(failure.getCause() instanceof DocumentNotFoundException);
                assertEquals(1, jdbc.queryForObject(
                        "SELECT COUNT(*) FROM document_access_logs"
                                + " WHERE document_id = ? AND actor_user_id = ?"
                                + " AND action = 'HEALTH_CERT_FILE_VIEW'"
                                + " AND result = 'DENIED'"
                                + " AND denial_reason = 'DOCUMENT_UNAVAILABLE'",
                        Integer.class,
                        fixture.healthDocumentId,
                        fixture.ownerId));
                assertEquals(0, jdbc.queryForObject(
                        "SELECT COUNT(*) FROM document_access_logs"
                                + " WHERE document_id = ? AND actor_user_id = ?"
                                + " AND action = 'HEALTH_CERT_FILE_VIEW'"
                                + " AND result = 'ALLOWED'",
                        Integer.class,
                        fixture.healthDocumentId,
                        fixture.ownerId));
            } finally {
                storage.releaseRead();
                executor.shutdownNow();
                deleteFixture(jdbc, fixture);
            }
        }
    }

    private Fixture insertFixture(JdbcTemplate jdbc) {
        String token = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        long ownerId = insertUser(jdbc, "da_owner_" + token, "OWNER", "김사장");
        long workerId = insertUser(jdbc, "da_worker_" + token, "WORKER", "김근로");
        long workplaceId = insertWorkplace(jdbc, ownerId, token);
        long workCaseId = insertWorkCase(jdbc, ownerId, workerId, workplaceId);
        insertContract(jdbc, workCaseId, ownerId, workerId);

        byte[] checksum = new byte[32];
        Arrays.fill(checksum, (byte) 7);
        long contractDocumentId = insertDocument(
                jdbc, ownerId, workCaseId, "EMPLOYMENT_CONTRACT", "2026-08-10", null);
        long contractVersionId = insertVersion(
                jdbc,
                contractDocumentId,
                2,
                "SIGNED",
                "access-tests/" + token + "/contract-v2.pdf",
                "application/pdf",
                checksum);

        long healthDocumentId = insertDocument(
                jdbc, workerId, null, "HEALTH_CERTIFICATE", "2026-08-01", "2027-08-11");
        long healthVersionId = insertVersion(
                jdbc,
                healthDocumentId,
                1,
                "ORIGINAL",
                "access-tests/" + token + "/health-v1.jpg",
                "image/jpeg",
                checksum);
        jdbc.update(
                "INSERT INTO document_shares"
                        + " (document_id, work_case_id, shared_with_user_id, purpose, status)"
                        + " VALUES (?, ?, ?, 'HEALTH_CERTIFICATE', 'ACTIVE')",
                healthDocumentId, workCaseId, ownerId);
        long shareId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);

        return new Fixture(
                ownerId,
                workerId,
                workplaceId,
                workCaseId,
                contractDocumentId,
                contractVersionId,
                healthDocumentId,
                healthVersionId,
                shareId,
                checksum);
    }

    private long insertUser(
            JdbcTemplate jdbc, String loginId, String role, String name) {
        jdbc.update(
                "INSERT INTO users"
                        + " (login_id, email, password_hash, name, role, status)"
                        + " VALUES (?, ?, 'test-hash', ?, ?, 'ACTIVE')",
                loginId, loginId + "@example.test", name, role);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private long insertWorkplace(JdbcTemplate jdbc, long ownerId, String token) {
        String registration = String.format(
                "%010d", Math.floorMod(token.hashCode(), 1_000_000_000));
        jdbc.update(
                "INSERT INTO workplaces"
                        + " (owner_user_id, business_registration_number, name,"
                        + " representative_name, road_address, phone, status)"
                        + " VALUES (?, ?, '강남점', '김대표', '서울 테스트로 1',"
                        + " '0212345678', 'ACTIVE')",
                ownerId, registration);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private long insertWorkCase(
            JdbcTemplate jdbc, long ownerId, long workerId, long workplaceId) {
        jdbc.update(
                "INSERT INTO work_cases"
                        + " (employer_id, worker_id, workplace_id, title, starts_at, ends_at,"
                        + " break_minutes, break_paid, workplace_name, workplace_address,"
                        + " allowed_radius_meters, agreed_wage, terms_version, status)"
                        + " VALUES (?, ?, ?, '파일 접근 테스트', '2026-08-20 10:00:00',"
                        + " '2026-08-20 18:00:00', 0, 0, '강남점', '서울 테스트로 1',"
                        + " 100, 90000, 1, 'ACCEPTED')",
                ownerId, workerId, workplaceId);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private void insertContract(
            JdbcTemplate jdbc, long workCaseId, long ownerId, long workerId) {
        jdbc.update(
                "INSERT INTO work_contracts"
                        + " (work_case_id, employer_id, worker_id, title, starts_at, ends_at,"
                        + " break_minutes, break_paid, workplace_name, workplace_address,"
                        + " allowed_radius_meters, agreed_wage, source_terms_version,"
                        + " terms_snapshot, accepted_at)"
                        + " VALUES (?, ?, ?, '파일 접근 테스트', '2026-08-20 10:00:00',"
                        + " '2026-08-20 18:00:00', 0, 0, '강남점', '서울 테스트로 1',"
                        + " 100, 90000, 1, '{}', '2026-08-10 10:00:00')",
                workCaseId, ownerId, workerId);
    }

    private long insertDocument(
            JdbcTemplate jdbc,
            long ownerId,
            Long workCaseId,
            String type,
            String issuedOn,
            String expiresOn) {
        jdbc.update(
                "INSERT INTO documents"
                        + " (created_by_user_id, owner_user_id, work_case_id, document_type,"
                        + " status, issued_on, expires_on)"
                        + " VALUES (?, ?, ?, ?, 'ACTIVE', ?, ?)",
                ownerId, ownerId, workCaseId, type, issuedOn, expiresOn);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private long insertVersion(
            JdbcTemplate jdbc,
            long documentId,
            int versionNo,
            String versionType,
            String storageKey,
            String mimeType,
            byte[] checksum) {
        jdbc.update(
                "INSERT INTO document_versions"
                        + " (document_id, version_no, version_type, storage_key,"
                        + " mime_type, size_bytes, checksum)"
                        + " VALUES (?, ?, ?, ?, ?, 1, ?)",
                documentId, versionNo, versionType, storageKey, mimeType, checksum);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private void deleteFixture(JdbcTemplate jdbc, Fixture fixture) {
        jdbc.update("DELETE FROM document_access_logs WHERE document_id IN (?, ?)",
                fixture.contractDocumentId, fixture.healthDocumentId);
        jdbc.update("DELETE FROM document_shares WHERE id = ?", fixture.shareId);
        jdbc.update("DELETE FROM document_versions WHERE document_id IN (?, ?)",
                fixture.contractDocumentId, fixture.healthDocumentId);
        jdbc.update("DELETE FROM documents WHERE id IN (?, ?)",
                fixture.contractDocumentId, fixture.healthDocumentId);
        jdbc.update("DELETE FROM work_contracts WHERE work_case_id = ?", fixture.workCaseId);
        jdbc.update("DELETE FROM work_cases WHERE id = ?", fixture.workCaseId);
        jdbc.update("DELETE FROM workplaces WHERE id = ?", fixture.workplaceId);
        jdbc.update("DELETE FROM users WHERE id IN (?, ?)", fixture.ownerId, fixture.workerId);
    }

    private record Fixture(
            long ownerId,
            long workerId,
            long workplaceId,
            long workCaseId,
            long contractDocumentId,
            long contractVersionId,
            long healthDocumentId,
            long healthVersionId,
            long shareId,
            byte[] checksum) {
    }

    /** 실제 파일 I/O 구간을 멈춰 DB Transaction이 이미 끝났는지 관찰합니다. */
    private static final class BlockingDocumentStorageAdapter implements DocumentStorageAdapter {

        private final CountDownLatch readStarted = new CountDownLatch(1);
        private final CountDownLatch releaseRead = new CountDownLatch(1);

        @Override
        public void writePending(String pendingKey, byte[] content) {
            throw new UnsupportedOperationException("읽기 경합 테스트에서는 쓰지 않습니다.");
        }

        @Override
        public void promote(String pendingKey, String finalKey, byte[] expectedSha256) {
            throw new UnsupportedOperationException("보건증 읽기에는 승격이 없습니다.");
        }

        @Override
        public byte[] read(String key) {
            readStarted.countDown();
            try {
                if (!releaseRead.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("파일 읽기 잠금 해제 시간 초과");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(exception);
            }
            return FILE_CONTENT;
        }

        @Override
        public boolean exists(String key) {
            return true;
        }

        @Override
        public void deletePending(String pendingKey) {
            throw new UnsupportedOperationException("읽기 경합 테스트에서는 삭제하지 않습니다.");
        }

        @Override
        public void deletePendingByWorkCaseId(
                long workCaseId,
                Set<Long> retainedDocumentIds) {
            throw new UnsupportedOperationException("읽기 경합 테스트에서는 삭제하지 않습니다.");
        }

        boolean awaitReadStarted() throws InterruptedException {
            return readStarted.await(5, TimeUnit.SECONDS);
        }

        void releaseRead() {
            releaseRead.countDown();
        }
    }
}
