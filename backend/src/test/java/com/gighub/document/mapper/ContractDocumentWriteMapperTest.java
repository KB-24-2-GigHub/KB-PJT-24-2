package com.gighub.document.mapper;

import com.gighub.config.RootConfig;
import com.gighub.document.mapper.param.DocumentInsertParam;
import com.gighub.document.mapper.result.ContractRetentionCandidateRow;
import com.gighub.document.mapper.result.ContractRetentionVersionKeyRow;
import com.gighub.document.mapper.result.DocumentOwnershipRow;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 보건증 등록·수정이 쓰는 documents 행 삽입·수정 SQL을 실제 MySQL에서 검증합니다. */
@Tag("database")
class ContractDocumentWriteMapperTest {

    @Test
    void insertsExpiresOnAndUpdatesIssuedDateOnlyForTheOwnedActiveHealthCertificate() {
        try (AnnotationConfigApplicationContext context =
                     new AnnotationConfigApplicationContext(RootConfig.class)) {
            JdbcTemplate jdbc = new JdbcTemplate(context.getBean(DataSource.class));
            ContractDocumentWriteMapper mapper = context.getBean(ContractDocumentWriteMapper.class);
            String token = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
            long ownerId = insertUser(jdbc, "cw_owner_" + token, "박근로");
            long otherUserId = insertUser(jdbc, "cw_other_" + token, "이근로");

            DocumentInsertParam insertParam = DocumentInsertParam.builder()
                    .createdByUserId(ownerId)
                    .ownerUserId(ownerId)
                    .workCaseId(null)
                    .documentType("HEALTH_CERTIFICATE")
                    .status("ACTIVE")
                    .issuedOn(LocalDate.of(2026, 8, 1))
                    .expiresOn(LocalDate.of(2027, 8, 1))
                    .build();

            try {
                assertEquals(1, mapper.insertDocument(insertParam));
                long documentId = insertParam.getId();

                assertEquals(
                        LocalDate.of(2027, 8, 1),
                        jdbc.queryForObject(
                                "SELECT expires_on FROM documents WHERE id = ?",
                                LocalDate.class, documentId));

                assertEquals(0, mapper.updateHealthCertificateIssuedDate(
                        documentId, otherUserId, LocalDate.of(2026, 9, 1), LocalDate.of(2027, 9, 1)));

                assertEquals(1, mapper.updateHealthCertificateIssuedDate(
                        documentId, ownerId, LocalDate.of(2026, 9, 1), LocalDate.of(2027, 9, 1)));
                assertEquals(
                        LocalDate.of(2026, 9, 1),
                        jdbc.queryForObject(
                                "SELECT issued_on FROM documents WHERE id = ?",
                                LocalDate.class, documentId));
                assertEquals(
                        LocalDate.of(2027, 9, 1),
                        jdbc.queryForObject(
                                "SELECT expires_on FROM documents WHERE id = ?",
                                LocalDate.class, documentId));

                jdbc.update("UPDATE documents SET status = 'DELETED' WHERE id = ?", documentId);
                assertEquals(0, mapper.updateHealthCertificateIssuedDate(
                        documentId, ownerId, LocalDate.of(2026, 10, 1), LocalDate.of(2027, 10, 1)));
            } finally {
                jdbc.update("DELETE FROM documents WHERE id = ?", insertParam.getId());
                jdbc.update("DELETE FROM users WHERE id IN (?, ?)", ownerId, otherUserId);
            }
        }
    }

    @Test
    void locksAndDeletesAnOwnedHealthCertificateAndRevokesItsActiveShareOnly() {
        try (AnnotationConfigApplicationContext context =
                     new AnnotationConfigApplicationContext(RootConfig.class)) {
            JdbcTemplate jdbc = new JdbcTemplate(context.getBean(DataSource.class));
            ContractDocumentWriteMapper mapper = context.getBean(ContractDocumentWriteMapper.class);
            String token = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
            long workerId = insertUser(jdbc, "cw_del_worker_" + token, "박근로");
            long ownerId = insertUser(jdbc, "cw_del_owner_" + token, "박사장");
            long workplaceId = insertWorkplace(jdbc, ownerId, token);
            long workCaseId = insertWorkCase(jdbc, ownerId, workerId, workplaceId);

            long documentId = insertDocument(jdbc, workerId, "ACTIVE");
            long shareId = insertActiveShare(jdbc, documentId, workCaseId, ownerId);

            try {
                assertNull(mapper.lockOwnDocument(documentId, ownerId));

                DocumentOwnershipRow locked = mapper.lockOwnDocument(documentId, workerId);
                assertEquals("HEALTH_CERTIFICATE", locked.getDocumentType());
                assertEquals("ACTIVE", locked.getStatus());

                assertEquals(1, mapper.deleteHealthCertificate(documentId));
                assertEquals("DELETED", mapper.lockOwnDocument(documentId, workerId).getStatus());

                // 이미 삭제된 문서는 다시 전이되지 않는다.
                assertEquals(0, mapper.deleteHealthCertificate(documentId));

                LocalDateTime revokedAt = LocalDateTime.of(2026, 8, 14, 12, 0);
                assertEquals(1, mapper.revokeActiveShares(documentId, revokedAt));
                assertEquals("REVOKED", jdbc.queryForObject(
                        "SELECT status FROM document_shares WHERE id = ?", String.class, shareId));
                assertEquals(revokedAt, jdbc.queryForObject(
                        "SELECT revoked_at FROM document_shares WHERE id = ?",
                        LocalDateTime.class, shareId));

                // 이미 REVOKED인 공유는 다시 바뀌지 않는다.
                assertEquals(0, mapper.revokeActiveShares(documentId, revokedAt.plusMinutes(1)));
            } finally {
                jdbc.update("DELETE FROM document_shares WHERE document_id = ?", documentId);
                jdbc.update("DELETE FROM documents WHERE id = ?", documentId);
                jdbc.update("DELETE FROM work_cases WHERE id = ?", workCaseId);
                jdbc.update("DELETE FROM workplaces WHERE id = ?", workplaceId);
                jdbc.update("DELETE FROM users WHERE id IN (?, ?)", workerId, ownerId);
            }
        }
    }

    @Test
    void findsContractRetentionCandidatesAtTheThreeYearBoundaryAndFlagsOrphans() {
        try (AnnotationConfigApplicationContext context =
                     new AnnotationConfigApplicationContext(RootConfig.class)) {
            JdbcTemplate jdbc = new JdbcTemplate(context.getBean(DataSource.class));
            ContractDocumentWriteMapper mapper = context.getBean(ContractDocumentWriteMapper.class);
            String token = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
            long workerId = insertUser(jdbc, "cw_ret_worker_" + token, "박근로");
            long ownerId = insertUser(jdbc, "cw_ret_owner_" + token, "박사장");
            long workplaceId = insertWorkplace(jdbc, ownerId, token);

            LocalDate today = LocalDate.now();
            long eligibleWorkCaseId = insertWorkCaseWithEndsAt(
                    jdbc, ownerId, workerId, workplaceId, today.minusYears(3).atStartOfDay());
            long notYetEligibleWorkCaseId = insertWorkCaseWithEndsAt(
                    jdbc, ownerId, workerId, workplaceId,
                    today.minusYears(3).plusDays(1).atStartOfDay());

            long eligibleDocumentId = insertContractDocument(
                    jdbc, ownerId, eligibleWorkCaseId, "ACTIVE");
            long notYetEligibleDocumentId = insertContractDocument(
                    jdbc, ownerId, notYetEligibleWorkCaseId, "ACTIVE");
            long orphanDocumentId = insertContractDocument(jdbc, ownerId, null, "ACTIVE");
            insertContractVersion(
                    jdbc, eligibleDocumentId, 1,
                    "contracts/%d/%d/v1.pdf".formatted(eligibleWorkCaseId, eligibleDocumentId));

            try {
                List<ContractRetentionCandidateRow> candidates =
                        mapper.findContractRetentionCandidates(100);
                List<Long> candidateIds = candidates.stream()
                        .map(ContractRetentionCandidateRow::getDocumentId).toList();
                assertTrue(candidateIds.contains(eligibleDocumentId));
                assertTrue(candidateIds.stream().noneMatch(id -> id.equals(notYetEligibleDocumentId)));
                assertTrue(candidateIds.stream().noneMatch(id -> id.equals(orphanDocumentId)));

                List<Long> orphanIds = mapper.findOrphanedContractDocumentIds(100);
                assertTrue(orphanIds.contains(orphanDocumentId));

                List<ContractRetentionVersionKeyRow> versions =
                        mapper.findVersionKeysByDocumentId(eligibleDocumentId);
                assertEquals(1, versions.size());
                assertEquals(eligibleWorkCaseId, versions.get(0).getWorkCaseId());
                assertEquals(1, versions.get(0).getVersionNo());

                assertEquals(1, mapper.markContractDeleted(eligibleDocumentId));
                assertEquals(
                        "DELETED", mapper.lockOwnDocument(eligibleDocumentId, ownerId).getStatus());
                // 이미 DELETED면 다시 전이되지 않는다(멱등 — 다음 실행이 재시도해도 안전).
                assertEquals(0, mapper.markContractDeleted(eligibleDocumentId));
            } finally {
                jdbc.update("DELETE FROM document_versions WHERE document_id = ?", eligibleDocumentId);
                jdbc.update(
                        "DELETE FROM documents WHERE id IN (?, ?, ?)",
                        eligibleDocumentId, notYetEligibleDocumentId, orphanDocumentId);
                jdbc.update(
                        "DELETE FROM work_cases WHERE id IN (?, ?)",
                        eligibleWorkCaseId, notYetEligibleWorkCaseId);
                jdbc.update("DELETE FROM workplaces WHERE id = ?", workplaceId);
                jdbc.update("DELETE FROM users WHERE id IN (?, ?)", workerId, ownerId);
            }
        }
    }

    private long insertUser(JdbcTemplate jdbc, String loginId, String name) {
        jdbc.update(
                "INSERT INTO users (login_id, email, password_hash, name, role, status)"
                        + " VALUES (?, ?, 'test-hash', ?, 'WORKER', 'ACTIVE')",
                loginId, loginId + "@example.test", name);
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

    private long insertWorkCase(JdbcTemplate jdbc, long ownerId, long workerId, long workplaceId) {
        jdbc.update(
                "INSERT INTO work_cases"
                        + " (employer_id, worker_id, workplace_id, title, starts_at, ends_at,"
                        + " break_minutes, break_paid, workplace_name, workplace_address,"
                        + " allowed_radius_meters, agreed_wage, terms_version, status)"
                        + " VALUES (?, ?, ?, '문서 삭제 테스트', '2026-08-20 10:00:00',"
                        + " '2026-08-20 18:00:00', 0, 0, '강남점', '서울 테스트로 1',"
                        + " 100, 90000, 1, 'ACCEPTED')",
                ownerId, workerId, workplaceId);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private long insertWorkCaseWithEndsAt(
            JdbcTemplate jdbc, long ownerId, long workerId, long workplaceId, LocalDateTime endsAt) {
        jdbc.update(
                "INSERT INTO work_cases"
                        + " (employer_id, worker_id, workplace_id, title, starts_at, ends_at,"
                        + " break_minutes, break_paid, workplace_name, workplace_address,"
                        + " allowed_radius_meters, agreed_wage, terms_version, status)"
                        + " VALUES (?, ?, ?, '보존 만료 테스트', ?, ?, 0, 0, '강남점', '서울 테스트로 1',"
                        + " 100, 90000, 1, 'ACCEPTED')",
                ownerId, workerId, workplaceId, endsAt.minusHours(8), endsAt);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private long insertContractDocument(
            JdbcTemplate jdbc, long ownerId, Long workCaseId, String status) {
        jdbc.update(
                "INSERT INTO documents"
                        + " (created_by_user_id, owner_user_id, work_case_id, document_type, status)"
                        + " VALUES (?, ?, ?, 'EMPLOYMENT_CONTRACT', ?)",
                ownerId, ownerId, workCaseId, status);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private void insertContractVersion(
            JdbcTemplate jdbc, long documentId, int versionNo, String storageKey) {
        jdbc.update(
                "INSERT INTO document_versions"
                        + " (document_id, version_no, version_type, storage_key, mime_type,"
                        + " size_bytes, checksum)"
                        + " VALUES (?, ?, 'ORIGINAL', ?, 'application/pdf', 1024, ?)",
                documentId, versionNo, storageKey, new byte[32]);
    }

    private long insertDocument(JdbcTemplate jdbc, long ownerId, String status) {
        jdbc.update(
                "INSERT INTO documents"
                        + " (created_by_user_id, owner_user_id, work_case_id, document_type,"
                        + " status, issued_on, expires_on)"
                        + " VALUES (?, ?, NULL, 'HEALTH_CERTIFICATE', ?, '2026-08-01', '2027-08-01')",
                ownerId, ownerId, status);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private long insertActiveShare(JdbcTemplate jdbc, long documentId, long workCaseId, long ownerId) {
        jdbc.update(
                "INSERT INTO document_shares"
                        + " (document_id, work_case_id, shared_with_user_id, purpose, status)"
                        + " VALUES (?, ?, ?, 'HEALTH_CERTIFICATE', 'ACTIVE')",
                documentId, workCaseId, ownerId);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }
}
