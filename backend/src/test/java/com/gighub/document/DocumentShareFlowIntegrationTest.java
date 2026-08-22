package com.gighub.document;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import javax.servlet.Filter;
import javax.servlet.http.Cookie;
import javax.sql.DataSource;

import com.gighub.auth.controller.AuthController;
import com.gighub.auth.security.AuthPrincipal;
import com.gighub.auth.security.AuthSessionManager;
import com.gighub.auth.service.AuthService;
import com.gighub.common.exception.CommonExceptionHandler;
import com.gighub.config.RootConfig;
import com.gighub.document.controller.DocumentController;
import com.gighub.document.service.DocumentDeleteService;
import com.gighub.document.service.DocumentQueryService;
import com.gighub.document.service.HealthCertificateRegisterService;
import com.gighub.document.service.HealthCertificateShareRevokeService;
import com.gighub.document.service.HealthCertificateShareService;
import com.gighub.document.service.HealthCertificateUpdateService;
import com.gighub.member.domain.UserRole;
import com.gighub.work.controller.WorkerController;
import com.gighub.work.service.WorkerQueryService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 실제 Security Filter Chain과 MySQL Head Schema로 보건증 공유 생성·철회의 IDOR 차단과 경쟁
 * 조건을 검증합니다(#181 검증 절).
 *
 * <p>계층별 Test(Validator·Service·Mapper 단위, DB 통합)는 각각 가짜 협력자나 직접 Mapper
 * 호출을 쓰므로 Session 인가·CSRF·Controller 역직렬화·Service 검증·DB 제약이 한 요청에서
 * 함께 동작하는지는 여기서만 확인할 수 있다.</p>
 */
@Tag("database")
class DocumentShareFlowIntegrationTest {

    private AnnotationConfigWebApplicationContext rootContext;
    private AnnotationConfigWebApplicationContext servletContext;
    private JdbcTemplate jdbc;
    private MockMvc mockMvc;

    private Long workerAId;
    private Long workerBId;
    private Long ownerAId;
    private Long ownerBId;
    private Long unrelatedOwnerId;
    private Long workplaceAId;
    private Long workplaceBId;
    private Long unrelatedWorkplaceId;
    private Long workCaseAId;
    private Long workCaseBId;
    private Long healthCertAId;
    private Long healthCertBId;
    private Long expiredHealthCertAId;
    private String businessNumberPrefix;

    @BeforeEach
    void setUp() {
        MockServletContext mockServletContext = new MockServletContext();

        rootContext = new AnnotationConfigWebApplicationContext();
        rootContext.setServletContext(mockServletContext);
        rootContext.register(RootConfig.class);
        rootContext.refresh();

        servletContext = new AnnotationConfigWebApplicationContext();
        servletContext.setServletContext(mockServletContext);
        servletContext.setParent(rootContext);
        servletContext.register(ServletTestConfig.class);
        servletContext.refresh();

        Filter securityFilter = rootContext.getBean("springSecurityFilterChain", Filter.class);
        mockMvc = MockMvcBuilders.webAppContextSetup(servletContext)
                .addFilters(securityFilter)
                .build();

        jdbc = new JdbcTemplate(rootContext.getBean(DataSource.class));
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        businessNumberPrefix = String.format(
                "%06d", ThreadLocalRandom.current().nextInt(1_000_000));

        workerAId = insertUser("sf181a" + suffix, "이알바", "WORKER");
        workerBId = insertUser("sf181b" + suffix, "박알바", "WORKER");
        ownerAId = insertUser("sf181o" + suffix, "김대표", "OWNER");
        ownerBId = insertUser("sf181p" + suffix, "최대표", "OWNER");
        unrelatedOwnerId = insertUser("sf181q" + suffix, "정대표", "OWNER");

        workplaceAId = insertWorkplace(ownerAId, 1);
        workplaceBId = insertWorkplace(ownerBId, 2);
        unrelatedWorkplaceId = insertWorkplace(unrelatedOwnerId, 3);

        workCaseAId = insertWorkCase(ownerAId, workerAId, workplaceAId);
        workCaseBId = insertWorkCase(ownerBId, workerAId, workplaceBId);

        healthCertAId = insertHealthCertificate(workerAId, "2027-08-01");
        healthCertBId = insertHealthCertificate(workerBId, "2027-08-01");
        expiredHealthCertAId = insertHealthCertificate(workerAId, "2026-01-01");
    }

    @AfterEach
    void tearDown() {
        try {
            jdbc.update(
                    "DELETE FROM document_shares WHERE document_id IN (?, ?, ?)",
                    healthCertAId, healthCertBId, expiredHealthCertAId);
            jdbc.update(
                    "DELETE FROM documents WHERE id IN (?, ?, ?)",
                    healthCertAId, healthCertBId, expiredHealthCertAId);
            jdbc.update(
                    "DELETE FROM notifications WHERE work_case_id IN (?, ?)",
                    workCaseAId, workCaseBId);
            jdbc.update("DELETE FROM work_cases WHERE id IN (?, ?)", workCaseAId, workCaseBId);
            jdbc.update(
                    "DELETE FROM workplaces WHERE id IN (?, ?, ?)",
                    workplaceAId, workplaceBId, unrelatedWorkplaceId);
            jdbc.update(
                    "DELETE FROM users WHERE id IN (?, ?, ?, ?, ?)",
                    workerAId, workerBId, ownerAId, ownerBId, unrelatedOwnerId);
        } finally {
            SecurityContextHolder.clearContext();
            servletContext.close();
            rootContext.close();
        }
    }

    @Test
    @Timeout(60)
    void workerListsSharesAndRevokesThroughRealFilterChain() throws Exception {
        MockHttpSession session = authenticatedSession(workerAId, UserRole.WORKER, "이알바");
        Cookie csrf = csrfCookie(session);

        mockMvc.perform(get("/api/worker/workplaces").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[*].workplaceId")
                        .value(org.hamcrest.Matchers.hasItem(workplaceAId.intValue())));

        MvcResult created = mockMvc.perform(
                        post("/api/documents/{documentId}/shares", healthCertAId)
                                .session(session)
                                .cookie(csrf)
                                .header("X-XSRF-TOKEN", csrf.getValue())
                                .contentType(APPLICATION_JSON)
                                .content(shareBody(workplaceAId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.shareId").isNumber())
                .andReturn();

        long shareId = created.getResponse().getContentAsString()
                .replaceAll(".*\"shareId\":(\\d+).*", "$1")
                .transform(Long::parseLong);

        // API는 workplaceId로 말하지만 저장은 work_case_id를 통해 그 사업장과 연결된다.
        Map<String, Object> shareRow = jdbc.queryForMap(
                "SELECT ds.status, wc.workplace_id FROM document_shares ds"
                        + " JOIN work_cases wc ON wc.id = ds.work_case_id WHERE ds.id = ?",
                shareId);
        assertEquals("ACTIVE", shareRow.get("status"));
        assertEquals(workplaceAId, ((Number) shareRow.get("workplace_id")).longValue());

        mockMvc.perform(get("/api/documents/{documentId}/shares", healthCertAId)
                        .session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].workplaceId").value(workplaceAId.intValue()))
                .andExpect(jsonPath("$.data.content[0].status").value("ACTIVE"));

        mockMvc.perform(delete("/api/documents/{documentId}/shares/{workplaceId}",
                        healthCertAId, workplaceAId)
                        .session(session)
                        .cookie(csrf)
                        .header("X-XSRF-TOKEN", csrf.getValue()))
                .andExpect(status().isNoContent());

        assertEquals("REVOKED", jdbc.queryForObject(
                "SELECT status FROM document_shares WHERE id = ?", String.class, shareId));

        // 대상이 이미 철회된 재요청도 소유자 요청이면 204다(멱등).
        mockMvc.perform(delete("/api/documents/{documentId}/shares/{workplaceId}",
                        healthCertAId, workplaceAId)
                        .session(session)
                        .cookie(csrf)
                        .header("X-XSRF-TOKEN", csrf.getValue()))
                .andExpect(status().isNoContent());
    }

    @Test
    @Timeout(60)
    void ownerIsRejectedFromCreatingAShareWithApprovedRoleMismatchEnvelope() throws Exception {
        MockHttpSession session = authenticatedSession(ownerAId, UserRole.OWNER, "김대표");
        Cookie csrf = csrfCookie(session);

        mockMvc.perform(post("/api/documents/{documentId}/shares", healthCertAId)
                        .session(session)
                        .cookie(csrf)
                        .header("X-XSRF-TOKEN", csrf.getValue())
                        .contentType(APPLICATION_JSON)
                        .content(shareBody(workplaceAId)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ROLE_MISMATCH"));

        assertEquals(0, countShares(healthCertAId));
    }

    @Test
    @Timeout(60)
    void requestWithoutSessionIsRejectedByFilterChain() throws Exception {
        Cookie csrf = anonymousCsrfCookie();

        mockMvc.perform(post("/api/documents/{documentId}/shares", healthCertAId)
                        .cookie(csrf)
                        .header("X-XSRF-TOKEN", csrf.getValue())
                        .contentType(APPLICATION_JSON)
                        .content(shareBody(workplaceAId)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));

        assertEquals(0, countShares(healthCertAId));
    }

    @Test
    @Timeout(60)
    void requestWithoutCsrfTokenIsRejected() throws Exception {
        MockHttpSession session = authenticatedSession(workerAId, UserRole.WORKER, "이알바");

        mockMvc.perform(post("/api/documents/{documentId}/shares", healthCertAId)
                        .session(session)
                        .contentType(APPLICATION_JSON)
                        .content(shareBody(workplaceAId)))
                .andExpect(status().isForbidden());

        assertEquals(0, countShares(healthCertAId));
    }

    /** 타 WORKER의 보건증은 존재를 숨기는 404이며, 400·403으로 구분해 존재를 드러내지 않는다. */
    @Test
    @Timeout(60)
    void sharingAnotherWorkersDocumentIsHiddenBehindNotFound() throws Exception {
        MockHttpSession session = authenticatedSession(workerAId, UserRole.WORKER, "이알바");
        Cookie csrf = csrfCookie(session);

        mockMvc.perform(post("/api/documents/{documentId}/shares", healthCertBId)
                        .session(session)
                        .cookie(csrf)
                        .header("X-XSRF-TOKEN", csrf.getValue())
                        .contentType(APPLICATION_JSON)
                        .content(shareBody(workplaceAId)))
                .andExpect(status().isNotFound());

        assertEquals(0, countShares(healthCertBId));
    }

    /** 근무 관계가 없는 사업장은 사업장 없음·비활성·후보 없음과 같은 400이다. */
    @Test
    @Timeout(60)
    void sharingAnUnrelatedWorkplaceIsRejectedAsAWorkplaceFieldError() throws Exception {
        MockHttpSession session = authenticatedSession(workerAId, UserRole.WORKER, "이알바");
        Cookie csrf = csrfCookie(session);

        mockMvc.perform(post("/api/documents/{documentId}/shares", healthCertAId)
                        .session(session)
                        .cookie(csrf)
                        .header("X-XSRF-TOKEN", csrf.getValue())
                        .contentType(APPLICATION_JSON)
                        .content(shareBody(unrelatedWorkplaceId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("workplaceId"));

        assertEquals(0, countShares(healthCertAId));
    }

    /** 만료된 보건증은 404가 아니라 사용자가 원인을 알 수 있는 400 workplaceId 오류다. */
    @Test
    @Timeout(60)
    void sharingAnExpiredHealthCertificateIsRejectedAsAWorkplaceFieldError() throws Exception {
        MockHttpSession session = authenticatedSession(workerAId, UserRole.WORKER, "이알바");
        Cookie csrf = csrfCookie(session);

        mockMvc.perform(post("/api/documents/{documentId}/shares", expiredHealthCertAId)
                        .session(session)
                        .cookie(csrf)
                        .header("X-XSRF-TOKEN", csrf.getValue())
                        .contentType(APPLICATION_JSON)
                        .content(shareBody(workplaceAId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("workplaceId"))
                .andExpect(jsonPath("$.fieldErrors[0].reason").value("DOCUMENT_EXPIRED"));

        assertEquals(0, countShares(expiredHealthCertAId));
    }

    @Test
    @Timeout(60)
    void duplicateActiveShareRequestReturnsConflictInsteadOfARepeatSuccess() throws Exception {
        MockHttpSession session = authenticatedSession(workerAId, UserRole.WORKER, "이알바");
        Cookie csrf = csrfCookie(session);

        mockMvc.perform(post("/api/documents/{documentId}/shares", healthCertAId)
                        .session(session)
                        .cookie(csrf)
                        .header("X-XSRF-TOKEN", csrf.getValue())
                        .contentType(APPLICATION_JSON)
                        .content(shareBody(workplaceAId)))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/documents/{documentId}/shares", healthCertAId)
                        .session(session)
                        .cookie(csrf)
                        .header("X-XSRF-TOKEN", csrf.getValue())
                        .contentType(APPLICATION_JSON)
                        .content(shareBody(workplaceAId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));

        assertEquals(1, countActiveShares(healthCertAId));
    }

    /** 같은 사업장으로 동시에 두 요청을 보내도 ACTIVE 공유는 한 건만 남는다. */
    @Test
    @Timeout(60)
    void concurrentShareRequestsCreateExactlyOneActiveShare() throws Exception {
        MockHttpSession session = authenticatedSession(workerAId, UserRole.WORKER, "이알바");
        Cookie csrf = csrfCookie(session);

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Callable<Integer> attempt = () -> {
                start.await();
                return mockMvc.perform(post("/api/documents/{documentId}/shares", healthCertAId)
                                .session(session)
                                .cookie(csrf)
                                .header("X-XSRF-TOKEN", csrf.getValue())
                                .contentType(APPLICATION_JSON)
                                .content(shareBody(workplaceAId)))
                        .andReturn()
                        .getResponse()
                        .getStatus();
            };

            List<Future<Integer>> results =
                    List.of(executor.submit(attempt), executor.submit(attempt));
            start.countDown();

            int createdCount = 0;
            int conflictCount = 0;
            for (Future<Integer> result : results) {
                int status = result.get(30, TimeUnit.SECONDS);
                if (status == 201) {
                    createdCount++;
                } else if (status == 409) {
                    conflictCount++;
                }
            }

            assertEquals(1, createdCount, "동시 요청에서 한 건만 생성돼야 합니다.");
            assertEquals(1, conflictCount, "나머지 요청은 승인된 충돌로 끝나야 합니다.");
            assertEquals(1, countActiveShares(healthCertAId));
        } finally {
            executor.shutdownNow();
        }
    }

    /** 타 WORKER의 문서에 딸린 공유를 철회하려는 시도는 존재를 숨기는 404다. */
    @Test
    @Timeout(60)
    void revokingAnotherWorkersDocumentShareIsHiddenBehindNotFound() throws Exception {
        MockHttpSession session = authenticatedSession(workerAId, UserRole.WORKER, "이알바");
        Cookie csrf = csrfCookie(session);

        mockMvc.perform(delete("/api/documents/{documentId}/shares/{workplaceId}",
                        healthCertBId, workplaceAId)
                        .session(session)
                        .cookie(csrf)
                        .header("X-XSRF-TOKEN", csrf.getValue()))
                .andExpect(status().isNotFound());
    }

    /**
     * 한 문서가 두 사업장에 공유된 상태에서 하나만 철회하면 다른 하나는 그대로다(오철회 방지,
     * DOC-008). Controller·Service·Mapper 전 계층을 실제 HTTP 요청으로 통과시켜 확인한다.
     */
    @Test
    @Timeout(60)
    void revokingOneWorkplaceShareLeavesTheOtherWorkplaceShareActive() throws Exception {
        MockHttpSession session = authenticatedSession(workerAId, UserRole.WORKER, "이알바");
        Cookie csrf = csrfCookie(session);

        mockMvc.perform(post("/api/documents/{documentId}/shares", healthCertAId)
                        .session(session)
                        .cookie(csrf)
                        .header("X-XSRF-TOKEN", csrf.getValue())
                        .contentType(APPLICATION_JSON)
                        .content(shareBody(workplaceAId)))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/documents/{documentId}/shares", healthCertAId)
                        .session(session)
                        .cookie(csrf)
                        .header("X-XSRF-TOKEN", csrf.getValue())
                        .contentType(APPLICATION_JSON)
                        .content(shareBody(workplaceBId)))
                .andExpect(status().isCreated());

        mockMvc.perform(delete("/api/documents/{documentId}/shares/{workplaceId}",
                        healthCertAId, workplaceAId)
                        .session(session)
                        .cookie(csrf)
                        .header("X-XSRF-TOKEN", csrf.getValue()))
                .andExpect(status().isNoContent());

        assertEquals(1, countActiveShares(healthCertAId));
        List<Map<String, Object>> activeWorkplaces = jdbc.queryForList(
                "SELECT wc.workplace_id AS workplaceId FROM document_shares ds"
                        + " JOIN work_cases wc ON wc.id = ds.work_case_id"
                        + " WHERE ds.document_id = ? AND ds.status = 'ACTIVE'",
                healthCertAId);
        assertEquals(workplaceBId, ((Number) activeWorkplaces.get(0).get("workplaceId")).longValue());
    }

    private MockHttpSession authenticatedSession(Long userId, UserRole role, String name) {
        Authentication authentication = new UsernamePasswordAuthenticationToken(
                new AuthPrincipal(userId, role, name),
                null,
                List.of(new SimpleGrantedAuthority("ROLE_" + role.name())));
        SecurityContext securityContext = SecurityContextHolder.createEmptyContext();
        securityContext.setAuthentication(authentication);

        MockHttpSession session = new MockHttpSession();
        session.setAttribute(
                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                securityContext);
        return session;
    }

    private Cookie anonymousCsrfCookie() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/auth/csrf"))
                .andExpect(status().isNoContent())
                .andReturn();
        return result.getResponse().getCookie("XSRF-TOKEN");
    }

    private Cookie csrfCookie(MockHttpSession session) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/auth/csrf").session(session))
                .andExpect(status().isNoContent())
                .andReturn();
        return result.getResponse().getCookie("XSRF-TOKEN");
    }

    private String shareBody(long workplaceId) {
        return "{\"workplaceId\":" + workplaceId + "}";
    }

    private int countShares(long documentId) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM document_shares WHERE document_id = ?",
                Integer.class, documentId);
    }

    private int countActiveShares(long documentId) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM document_shares WHERE document_id = ? AND status = 'ACTIVE'",
                Integer.class, documentId);
    }

    private Long insertUser(String loginId, String name, String role) {
        jdbc.update(
                "INSERT INTO users (login_id, email, password_hash, name, role, status)"
                        + " VALUES (?, ?, 'test-hash', ?, ?, 'ACTIVE')",
                loginId, loginId + "@example.test", name, role);
        return jdbc.queryForObject("SELECT id FROM users WHERE login_id = ?", Long.class, loginId);
    }

    private Long insertWorkplace(Long ownerId, int sequence) {
        String registration = businessNumberPrefix + String.format("%04d", sequence);
        jdbc.update(
                "INSERT INTO workplaces"
                        + " (owner_user_id, business_registration_number, name,"
                        + " representative_name, road_address, phone, status)"
                        + " VALUES (?, ?, '강남점', '김대표', '서울 테스트로 1',"
                        + " '0212345678', 'ACTIVE')",
                ownerId, registration);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private Long insertWorkCase(Long employerId, Long workerId, Long workplaceId) {
        jdbc.update(
                "INSERT INTO work_cases"
                        + " (employer_id, worker_id, workplace_id, title, starts_at, ends_at,"
                        + " break_minutes, break_paid, workplace_name, workplace_address,"
                        + " allowed_radius_meters, agreed_wage, terms_version, status)"
                        + " VALUES (?, ?, ?, '공유 통합 테스트', '2026-09-01 09:00:00',"
                        + " '2026-09-01 18:00:00', 0, 0, '강남점', '서울 테스트로 1',"
                        + " 100, 90000, 1, 'ACCEPTED')",
                employerId, workerId, workplaceId);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private Long insertHealthCertificate(Long ownerId, String expiresOn) {
        jdbc.update(
                "INSERT INTO documents"
                        + " (created_by_user_id, owner_user_id, work_case_id, document_type,"
                        + " status, issued_on, expires_on)"
                        + " VALUES (?, ?, NULL, 'HEALTH_CERTIFICATE', 'ACTIVE', '2026-01-01', ?)",
                ownerId, ownerId, expiresOn);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    @Configuration
    @EnableWebMvc
    static class ServletTestConfig {

        @Bean
        DocumentController documentController(
                DocumentQueryService documentQueryService,
                HealthCertificateRegisterService healthCertificateRegisterService,
                HealthCertificateUpdateService healthCertificateUpdateService,
                DocumentDeleteService documentDeleteService,
                HealthCertificateShareService healthCertificateShareService,
                HealthCertificateShareRevokeService healthCertificateShareRevokeService) {
            return new DocumentController(
                    documentQueryService,
                    healthCertificateRegisterService,
                    healthCertificateUpdateService,
                    documentDeleteService,
                    healthCertificateShareService,
                    healthCertificateShareRevokeService);
        }

        @Bean
        WorkerController workerController(WorkerQueryService workerQueryService) {
            return new WorkerController(workerQueryService);
        }

        /** CSRF Token 발급은 실제 인증 Controller가 담당하므로 함께 등록한다. */
        @Bean
        AuthController authController(
                AuthService authService, AuthSessionManager authSessionManager) {
            return new AuthController(authService, authSessionManager);
        }

        @Bean
        CommonExceptionHandler commonExceptionHandler() {
            return new CommonExceptionHandler();
        }
    }
}
