package com.gighub.document.mapper;

import com.gighub.config.RootConfig;
import com.gighub.document.mapper.result.DocumentListRow;
import com.gighub.document.mapper.result.DocumentShareRow;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 역할별 OWN/SHARED 목록과 소유자 전용 공유 이력 SQL을 실제 MySQL에서 검증합니다. */
@Tag("database")
class DocumentQueryMapperTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 11, 12, 0);
    private static final LocalDate TODAY = NOW.toLocalDate();

    @Test
    void returnsOnlyRoleVisibleRowsAndSafeShareProjection() {
        try (AnnotationConfigApplicationContext context =
                     new AnnotationConfigApplicationContext(RootConfig.class)) {
            JdbcTemplate jdbc = new JdbcTemplate(context.getBean(DataSource.class));
            DocumentQueryMapper mapper = context.getBean(DocumentQueryMapper.class);
            Fixture fixture = insertFixture(jdbc);

            try {
                // 공유 자체의 과거 만료 시각은 무시하고 문서·사업장·업무 상태로 유효성을 판단한다.
                jdbc.update(
                        "UPDATE document_shares SET expires_at = ? WHERE id = ?",
                        NOW.minusDays(1),
                        fixture.shareId);

                List<DocumentListRow> ownerRows = mapper.findDocuments(
                        fixture.ownerId, "OWNER", null, null, NOW, TODAY, 0, 20);
                assertEquals(2, ownerRows.size());
                assertTrue(ownerRows.stream().anyMatch(row ->
                        row.getDocumentId().equals(fixture.contractDocumentId)
                                && "OWN".equals(row.getSource())));
                assertTrue(ownerRows.stream().anyMatch(row ->
                        row.getDocumentId().equals(fixture.healthDocumentId)
                                && "SHARED".equals(row.getSource())
                                && Long.valueOf(fixture.workCaseId).equals(row.getWorkCaseId())));
                assertEquals(2L, mapper.countDocuments(
                        fixture.ownerId, "OWNER", null, null, NOW, TODAY));

                List<DocumentListRow> workerRows = mapper.findDocuments(
                        fixture.workerId, "WORKER", null, null, NOW, TODAY, 0, 20);
                assertEquals(2, workerRows.size());
                assertTrue(workerRows.stream().anyMatch(row ->
                        row.getDocumentId().equals(fixture.healthDocumentId)
                                && "OWN".equals(row.getSource())
                                && row.getWorkplaceId() == null
                                && !Boolean.TRUE.equals(row.getCanShare())));
                assertTrue(workerRows.stream().anyMatch(row ->
                        row.getDocumentId().equals(fixture.contractDocumentId)
                                && "SHARED".equals(row.getSource())));

                List<DocumentListRow> filtered = mapper.findDocuments(
                        fixture.ownerId,
                        "OWNER",
                        fixture.workplaceId,
                        "HEALTH_CERTIFICATE",
                        NOW,
                        TODAY,
                        0,
                        20);
                assertEquals(1, filtered.size());
                assertEquals(fixture.healthDocumentId, filtered.get(0).getDocumentId());

                List<DocumentListRow> afterHealthExpiry = mapper.findDocuments(
                        fixture.workerId,
                        "WORKER",
                        null,
                        "HEALTH_CERTIFICATE",
                        LocalDateTime.of(2028, 1, 1, 0, 0),
                        LocalDate.of(2028, 1, 1),
                        0,
                        20);
                assertEquals(1, afterHealthExpiry.size());
                assertEquals("EXPIRED", afterHealthExpiry.get(0).getStatus());
                assertFalse(Boolean.TRUE.equals(afterHealthExpiry.get(0).getCanShare()));

                assertTrue(mapper.isOwnedActiveHealthDocument(
                        fixture.healthDocumentId, fixture.workerId));
                assertFalse(mapper.isOwnedActiveHealthDocument(
                        fixture.healthDocumentId, fixture.ownerId));

                List<DocumentShareRow> shares = mapper.findSharesByDocumentId(
                        fixture.healthDocumentId, NOW, TODAY, 0, 20);
                assertEquals(1, shares.size());
                assertEquals(1L, mapper.countSharesByDocumentId(fixture.healthDocumentId));
                assertEquals(fixture.shareId, shares.get(0).getShareId());
                assertEquals(fixture.workplaceId, shares.get(0).getWorkplaceId());
                assertEquals("ACTIVE", shares.get(0).getStatus());
                assertNull(shares.get(0).getRevokedAt());
                assertEquals(LocalDateTime.of(2026, 8, 20, 18, 0),
                        shares.get(0).getEffectiveUntil());

                // 노출 만료 시각은 Work Case 종료와 보건증 만료 다음 날 중 빠른 시각이다.
                jdbc.update(
                        "UPDATE work_cases SET ends_at = ? WHERE id = ?",
                        LocalDateTime.of(2028, 8, 20, 18, 0),
                        fixture.workCaseId);
                List<DocumentShareRow> certificateLimitedShares =
                        mapper.findSharesByDocumentId(
                                fixture.healthDocumentId, NOW, TODAY, 0, 20);
                assertEquals(LocalDateTime.of(2027, 8, 12, 0, 0),
                        certificateLimitedShares.get(0).getEffectiveUntil());
                jdbc.update(
                        "UPDATE work_cases SET ends_at = ? WHERE id = ?",
                        LocalDateTime.of(2026, 8, 20, 18, 0),
                        fixture.workCaseId);

                assertTrue(mapper.findDocuments(
                        fixture.ownerId,
                        "OWNER",
                        null,
                        "HEALTH_CERTIFICATE",
                        LocalDateTime.of(2028, 1, 1, 0, 0),
                        LocalDate.of(2028, 1, 1),
                        0,
                        20).isEmpty());

                jdbc.update(
                        "UPDATE document_shares SET status = 'REVOKED', revoked_at = ? WHERE id = ?",
                        NOW,
                        fixture.shareId);
                assertTrue(mapper.findDocuments(
                        fixture.ownerId,
                        "OWNER",
                        null,
                        "HEALTH_CERTIFICATE",
                        NOW,
                        TODAY,
                        0,
                        20).isEmpty());
                List<DocumentListRow> workerRowsAfterRevocation = mapper.findDocuments(
                        fixture.workerId,
                        "WORKER",
                        null,
                        "HEALTH_CERTIFICATE",
                        NOW,
                        TODAY,
                        0,
                        20);
                assertEquals(1, workerRowsAfterRevocation.size());
                assertTrue(Boolean.TRUE.equals(
                        workerRowsAfterRevocation.get(0).getCanShare()));

                LocalDateTime afterWorkCaseEnd = LocalDateTime.of(2026, 8, 20, 18, 1);
                List<DocumentListRow> afterAcceptedCaseEnd = mapper.findDocuments(
                        fixture.workerId,
                        "WORKER",
                        null,
                        "HEALTH_CERTIFICATE",
                        afterWorkCaseEnd,
                        afterWorkCaseEnd.toLocalDate(),
                        0,
                        20);
                assertFalse(Boolean.TRUE.equals(
                        afterAcceptedCaseEnd.get(0).getCanShare()));

                jdbc.update(
                        "UPDATE work_cases SET status = 'READY' WHERE id = ?",
                        fixture.workCaseId);
                List<DocumentListRow> afterReadyCaseEnd = mapper.findDocuments(
                        fixture.workerId,
                        "WORKER",
                        null,
                        "HEALTH_CERTIFICATE",
                        afterWorkCaseEnd,
                        afterWorkCaseEnd.toLocalDate(),
                        0,
                        20);
                assertFalse(Boolean.TRUE.equals(
                        afterReadyCaseEnd.get(0).getCanShare()));
            } finally {
                deleteFixture(jdbc, fixture);
            }
        }
    }

    @Test
    void marksMalformedShareRelationshipsExpired() {
        try (AnnotationConfigApplicationContext context =
                     new AnnotationConfigApplicationContext(RootConfig.class)) {
            JdbcTemplate jdbc = new JdbcTemplate(context.getBean(DataSource.class));
            DocumentQueryMapper mapper = context.getBean(DocumentQueryMapper.class);
            Fixture fixture = insertFixture(jdbc);
            String token = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
            long otherOwnerId = insertUser(
                    jdbc, "dq_other_owner_" + token, "OWNER", "박사장");

            try {
                jdbc.update(
                        "UPDATE documents SET owner_user_id = ? WHERE id = ?",
                        otherOwnerId,
                        fixture.healthDocumentId);
                List<DocumentShareRow> workerMismatchShares =
                        mapper.findSharesByDocumentId(
                                fixture.healthDocumentId, NOW, TODAY, 0, 20);
                assertEquals(1, workerMismatchShares.size());
                assertEquals("EXPIRED", workerMismatchShares.get(0).getStatus());

                jdbc.update(
                        "UPDATE documents SET owner_user_id = ? WHERE id = ?",
                        fixture.workerId,
                        fixture.healthDocumentId);
                jdbc.update(
                        "UPDATE document_shares SET shared_with_user_id = ? WHERE id = ?",
                        otherOwnerId,
                        fixture.shareId);
                List<DocumentShareRow> recipientMismatchShares =
                        mapper.findSharesByDocumentId(
                                fixture.healthDocumentId, NOW, TODAY, 0, 20);
                assertEquals(1, recipientMismatchShares.size());
                assertEquals("EXPIRED", recipientMismatchShares.get(0).getStatus());
            } finally {
                deleteFixture(jdbc, fixture);
                jdbc.update("DELETE FROM users WHERE id = ?", otherOwnerId);
            }
        }
    }

    @Test
    void pagesShareHistoryInNewestCreationOrder() {
        try (AnnotationConfigApplicationContext context =
                     new AnnotationConfigApplicationContext(RootConfig.class)) {
            JdbcTemplate jdbc = new JdbcTemplate(context.getBean(DataSource.class));
            DocumentQueryMapper mapper = context.getBean(DocumentQueryMapper.class);
            Fixture fixture = insertFixture(jdbc);

            try {
                LocalDateTime olderCreatedAt = NOW.minusMinutes(2);
                LocalDateTime newerCreatedAt = NOW.minusMinutes(1);
                jdbc.update(
                        "UPDATE document_shares SET created_at = ? WHERE id = ?",
                        olderCreatedAt,
                        fixture.shareId);
                jdbc.update(
                        "INSERT INTO document_shares"
                                + " (document_id, work_case_id, shared_with_user_id, purpose,"
                                + " status, revoked_at, created_at)"
                                + " VALUES (?, ?, ?, 'HEALTH_CERTIFICATE', 'REVOKED', ?, ?)",
                        fixture.healthDocumentId,
                        fixture.workCaseId,
                        fixture.ownerId,
                        newerCreatedAt,
                        newerCreatedAt);
                long newerShareId = jdbc.queryForObject(
                        "SELECT LAST_INSERT_ID()", Long.class);

                assertEquals(2L, mapper.countSharesByDocumentId(fixture.healthDocumentId));

                List<DocumentShareRow> firstPage = mapper.findSharesByDocumentId(
                        fixture.healthDocumentId, NOW, TODAY, 0, 1);
                List<DocumentShareRow> secondPage = mapper.findSharesByDocumentId(
                        fixture.healthDocumentId, NOW, TODAY, 1, 1);
                List<DocumentShareRow> afterLastPage = mapper.findSharesByDocumentId(
                        fixture.healthDocumentId, NOW, TODAY, 2, 1);

                assertEquals(List.of(newerShareId), firstPage.stream()
                        .map(DocumentShareRow::getShareId)
                        .toList());
                assertEquals("REVOKED", firstPage.get(0).getStatus());
                assertEquals(List.of(fixture.shareId), secondPage.stream()
                        .map(DocumentShareRow::getShareId)
                        .toList());
                assertTrue(afterLastPage.isEmpty());
            } finally {
                deleteFixture(jdbc, fixture);
            }
        }
    }

    @Test
    void findsOwnHealthCertificateByIdAndHidesItFromOthersOrOtherTypes() {
        try (AnnotationConfigApplicationContext context =
                     new AnnotationConfigApplicationContext(RootConfig.class)) {
            JdbcTemplate jdbc = new JdbcTemplate(context.getBean(DataSource.class));
            DocumentQueryMapper mapper = context.getBean(DocumentQueryMapper.class);
            Fixture fixture = insertFixture(jdbc);

            try {
                DocumentListRow own = mapper.findOwnHealthCertificateById(
                        fixture.workerId, fixture.healthDocumentId, TODAY);
                assertEquals(fixture.healthDocumentId, own.getDocumentId());
                assertEquals("ACTIVE", own.getStatus());
                assertEquals("OWN", own.getSource());

                assertEquals("EXPIRED", mapper.findOwnHealthCertificateById(
                                fixture.workerId, fixture.healthDocumentId,
                                LocalDate.of(2028, 1, 1))
                        .getStatus());

                assertNull(mapper.findOwnHealthCertificateById(
                        fixture.ownerId, fixture.healthDocumentId, TODAY));
                assertNull(mapper.findOwnHealthCertificateById(
                        fixture.workerId, fixture.contractDocumentId, TODAY));
            } finally {
                deleteFixture(jdbc, fixture);
            }
        }
    }

    private Fixture insertFixture(JdbcTemplate jdbc) {
        String token = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        long ownerId = insertUser(jdbc, "dq_owner_" + token, "OWNER", "김사장");
        long workerId = insertUser(jdbc, "dq_worker_" + token, "WORKER", "김근로");
        long workplaceId = insertWorkplace(jdbc, ownerId, token);
        long workCaseId = insertWorkCase(jdbc, ownerId, workerId, workplaceId);
        insertContract(jdbc, workCaseId, ownerId, workerId);

        long contractDocumentId = insertDocument(
                jdbc, ownerId, workCaseId, "EMPLOYMENT_CONTRACT", "2026-08-10", null);
        insertVersion(jdbc, contractDocumentId, 2, "SIGNED",
                "query-tests/" + token + "/contract-v2.pdf", "application/pdf");

        long healthDocumentId = insertDocument(
                jdbc, workerId, null, "HEALTH_CERTIFICATE", "2026-08-01", "2027-08-11");
        insertVersion(jdbc, healthDocumentId, 1, "ORIGINAL",
                "query-tests/" + token + "/health-v1.jpg", "image/jpeg");

        jdbc.update(
                "INSERT INTO document_shares"
                        + " (document_id, work_case_id, shared_with_user_id, purpose, status)"
                        + " VALUES (?, ?, ?, 'HEALTH_CERTIFICATE', 'ACTIVE')",
                healthDocumentId, workCaseId, ownerId);
        long shareId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        return new Fixture(ownerId, workerId, workplaceId, workCaseId,
                contractDocumentId, healthDocumentId, shareId);
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
                        + " VALUES (?, ?, ?, '문서 조회 테스트', '2026-08-20 10:00:00',"
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
                        + " VALUES (?, ?, ?, '문서 조회 테스트', '2026-08-20 10:00:00',"
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

    private void insertVersion(
            JdbcTemplate jdbc,
            long documentId,
            int versionNo,
            String versionType,
            String storageKey,
            String mimeType) {
        byte[] checksum = new byte[32];
        Arrays.fill(checksum, (byte) 7);
        jdbc.update(
                "INSERT INTO document_versions"
                        + " (document_id, version_no, version_type, storage_key,"
                        + " mime_type, size_bytes, checksum)"
                        + " VALUES (?, ?, ?, ?, ?, 1, ?)",
                documentId, versionNo, versionType, storageKey, mimeType, checksum);
    }

    private void deleteFixture(JdbcTemplate jdbc, Fixture fixture) {
        jdbc.update("DELETE FROM document_shares WHERE document_id = ?",
                fixture.healthDocumentId);
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
            long healthDocumentId,
            long shareId) {
    }
}
