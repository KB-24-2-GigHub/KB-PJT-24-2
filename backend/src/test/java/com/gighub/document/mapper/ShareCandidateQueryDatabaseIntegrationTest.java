package com.gighub.document.mapper;

import com.gighub.config.RootConfig;
import com.gighub.document.mapper.result.ShareCandidateRow;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 보건증 공유 생성 검증이 쓰는 두 조회 SQL을 실제 MySQL에서 확인합니다.
 *
 * <p>이 두 Query가 공유 요청의 404·400·409 경계를 그대로 결정하므로, 조건 하나가 빠지면
 * 관계 없는 사업장에 건강 정보를 붙일 수 있게 됩니다.</p>
 */
@Tag("database")
class ShareCandidateQueryDatabaseIntegrationTest {

    private static final LocalDateTime STARTS_AT = LocalDateTime.of(2026, 9, 1, 9, 0);
    private static final LocalDateTime ENDS_AT = LocalDateTime.of(2026, 9, 1, 18, 0);

    private String businessNumberPrefix;

    @Test
    @Timeout(60)
    void resolvesOnlyOwnActiveHealthCertificatesAndActiveWorkplaceCandidates() {
        try (AnnotationConfigApplicationContext context =
                     new AnnotationConfigApplicationContext(RootConfig.class)) {
            JdbcTemplate jdbc = new JdbcTemplate(context.getBean(DataSource.class));
            DocumentQueryMapper mapper = context.getBean(DocumentQueryMapper.class);

            String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
            businessNumberPrefix = String.format(
                    "%06d", ThreadLocalRandom.current().nextInt(1_000_000));

            long ownerId = insertUser(jdbc, "sc181o" + suffix, "김대표", "OWNER");
            long otherOwnerId = insertUser(jdbc, "sc181p" + suffix, "최대표", "OWNER");
            long workerId = insertUser(jdbc, "sc181a" + suffix, "이알바", "WORKER");
            long otherWorkerId = insertUser(jdbc, "sc181b" + suffix, "박알바", "WORKER");

            long activeWorkplaceId = insertWorkplace(jdbc, ownerId, 1, "ACTIVE");
            long inactiveWorkplaceId = insertWorkplace(jdbc, ownerId, 2, "INACTIVE");
            long mismatchedWorkplaceId = insertWorkplace(jdbc, ownerId, 3, "ACTIVE");

            long ownDocumentId = insertDocument(
                    jdbc, workerId, "HEALTH_CERTIFICATE", "ACTIVE", LocalDate.of(2027, 8, 1));
            long deletedDocumentId = insertDocument(
                    jdbc, workerId, "HEALTH_CERTIFICATE", "DELETED", LocalDate.of(2027, 8, 1));
            long otherWorkerDocumentId = insertDocument(
                    jdbc, otherWorkerId, "HEALTH_CERTIFICATE", "ACTIVE", LocalDate.of(2027, 8, 1));
            long contractDocumentId = insertDocument(
                    jdbc, workerId, "EMPLOYMENT_CONTRACT", "ACTIVE", null);

            List<Long> workCaseIds = new ArrayList<>();
            List<Long> workplaceIds = new ArrayList<>(
                    List.of(activeWorkplaceId, inactiveWorkplaceId, mismatchedWorkplaceId));
            try {
                // --- 문서 가시성: 소유 ACTIVE 보건증만 만료일을 돌려준다 ---
                assertEquals(
                        LocalDate.of(2027, 8, 1),
                        mapper.findOwnActiveHealthCertificateExpiry(ownDocumentId, workerId));
                assertNull(mapper.findOwnActiveHealthCertificateExpiry(deletedDocumentId, workerId));
                assertNull(mapper.findOwnActiveHealthCertificateExpiry(
                        otherWorkerDocumentId, workerId));
                assertNull(mapper.findOwnActiveHealthCertificateExpiry(
                        contractDocumentId, workerId));
                assertNull(mapper.findOwnActiveHealthCertificateExpiry(ownDocumentId, otherWorkerId));

                // --- 후보 결정 ---
                workCaseIds.add(insertWorkCase(
                        jdbc, ownerId, workerId, activeWorkplaceId, "ACCEPTED"));
                assertEquals(
                        1, mapper.findShareCandidates(workerId, activeWorkplaceId).size());

                // READY도 후보다. 같은 사업장 두 건은 접히지 않고 그대로 둘이다.
                workCaseIds.add(insertWorkCase(
                        jdbc, ownerId, workerId, activeWorkplaceId, "READY"));
                List<ShareCandidateRow> both =
                        mapper.findShareCandidates(workerId, activeWorkplaceId);
                assertEquals(2, both.size());
                assertEquals(ownerId, both.get(0).getOwnerUserId());

                // IN_PROGRESS는 접근 판정에서는 유효하지만 신규 공유 후보는 아니다.
                long inProgressWorkplaceId = insertWorkplace(jdbc, ownerId, 4, "ACTIVE");
                workplaceIds.add(inProgressWorkplaceId);
                workCaseIds.add(insertWorkCase(
                        jdbc, ownerId, workerId, inProgressWorkplaceId, "IN_PROGRESS"));
                assertTrue(mapper.findShareCandidates(workerId, inProgressWorkplaceId).isEmpty());

                // 비활성 사업장은 후보가 아니다.
                workCaseIds.add(insertWorkCase(
                        jdbc, ownerId, workerId, inactiveWorkplaceId, "ACCEPTED"));
                assertTrue(mapper.findShareCandidates(workerId, inactiveWorkplaceId).isEmpty());

                // employer_id와 workplaces.owner_user_id가 어긋난 관계는 조회로 거를 필요가
                // 없다. 복합 외래키가 그런 행 자체를 막는다는 것을 여기서 고정한다. 이 보장이
                // 사라지면 접근 판정이 두 값을 모두 대조하므로 생성 직후부터 영구히 EXPIRED로만
                // 보이는 공유가 만들어질 수 있다.
                assertThrows(DataIntegrityViolationException.class, () -> insertWorkCase(
                        jdbc, otherOwnerId, workerId, mismatchedWorkplaceId, "ACCEPTED"));

                // 타 WORKER의 관계는 보이지 않는다.
                assertTrue(mapper.findShareCandidates(otherWorkerId, activeWorkplaceId).isEmpty());
            } finally {
                // work_cases는 workplaces를 RESTRICT로 참조하므로 근무를 먼저 지운다.
                workCaseIds.forEach(id -> jdbc.update("DELETE FROM work_cases WHERE id = ?", id));
                jdbc.update("DELETE FROM documents WHERE id IN (?, ?, ?, ?)",
                        ownDocumentId, deletedDocumentId, otherWorkerDocumentId, contractDocumentId);
                workplaceIds.forEach(id -> jdbc.update("DELETE FROM workplaces WHERE id = ?", id));
                jdbc.update("DELETE FROM users WHERE id IN (?, ?, ?, ?)",
                        ownerId, otherOwnerId, workerId, otherWorkerId);
            }
        }
    }

    private long insertUser(JdbcTemplate jdbc, String loginId, String name, String role) {
        jdbc.update(
                "INSERT INTO users (login_id, email, password_hash, name, role, status)"
                        + " VALUES (?, ?, 'test-hash', ?, ?, 'ACTIVE')",
                loginId, loginId + "@example.test", name, role);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private long insertWorkplace(JdbcTemplate jdbc, long ownerId, int sequence, String status) {
        jdbc.update(
                "INSERT INTO workplaces"
                        + " (owner_user_id, business_registration_number, name,"
                        + " representative_name, road_address, phone, status)"
                        + " VALUES (?, ?, '강남점', '김대표', '서울 테스트로 1', '0212345678', ?)",
                ownerId, businessNumberPrefix + String.format("%04d", sequence), status);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private long insertDocument(
            JdbcTemplate jdbc, long ownerId, String documentType, String status, LocalDate expiresOn) {
        jdbc.update(
                "INSERT INTO documents"
                        + " (created_by_user_id, owner_user_id, work_case_id, document_type,"
                        + " status, issued_on, expires_on)"
                        + " VALUES (?, ?, NULL, ?, ?, '2026-08-01', ?)",
                ownerId, ownerId, documentType, status, expiresOn);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private long insertWorkCase(
            JdbcTemplate jdbc, long employerId, long workerId, long workplaceId, String status) {
        jdbc.update(
                "INSERT INTO work_cases"
                        + " (employer_id, worker_id, workplace_id, title, starts_at, ends_at,"
                        + " break_minutes, break_paid, workplace_name, workplace_address,"
                        + " allowed_radius_meters, agreed_wage, terms_version, status)"
                        + " VALUES (?, ?, ?, '공유 후보 테스트', ?, ?, 0, 0, '강남점',"
                        + " '서울 테스트로 1', 100, 90000, 1, ?)",
                employerId, workerId, workplaceId, STARTS_AT, ENDS_AT, status);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }
}
