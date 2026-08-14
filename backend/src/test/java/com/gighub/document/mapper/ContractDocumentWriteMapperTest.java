package com.gighub.document.mapper;

import com.gighub.config.RootConfig;
import com.gighub.document.mapper.param.DocumentInsertParam;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.time.LocalDate;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

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

    private long insertUser(JdbcTemplate jdbc, String loginId, String name) {
        jdbc.update(
                "INSERT INTO users (login_id, email, password_hash, name, role, status)"
                        + " VALUES (?, ?, 'test-hash', ?, 'WORKER', 'ACTIVE')",
                loginId, loginId + "@example.test", name);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }
}
