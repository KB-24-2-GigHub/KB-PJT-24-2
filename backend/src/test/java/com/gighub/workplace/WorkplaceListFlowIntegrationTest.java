package com.gighub.workplace;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import javax.servlet.Filter;
import javax.servlet.http.Cookie;
import javax.sql.DataSource;

import com.gighub.auth.controller.AuthController;
import com.gighub.auth.security.AuthPrincipal;
import com.gighub.auth.security.AuthSessionManager;
import com.gighub.auth.service.AuthService;
import com.gighub.common.exception.CommonExceptionHandler;
import com.gighub.config.RootConfig;
import com.gighub.workplace.geocoding.FixedAddressGeocoderConfig;
import com.gighub.member.domain.UserRole;
import com.gighub.workplace.controller.WorkplaceController;
import com.gighub.workplace.service.WorkplaceService;
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

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 실제 Security Filter Chain과 MySQL Head Schema로 사업장 목록 조회와 온보딩 연동을 검증합니다.
 *
 * <p>노출 범위, Page 경계, {@code needsWorkplaceSetup} 전이는 계층별 Test가 각각 가짜 협력자를
 * 쓰기 때문에 한 요청에서 함께 동작하는지는 여기서만 확인할 수 있습니다.</p>
 *
 * <p>{@code INACTIVE}·{@code DELETED}를 만드는 API는 아직 없으므로 해당 상태는 JdbcTemplate으로
 * 직접 넣습니다.</p>
 */
@Tag("database")
class WorkplaceListFlowIntegrationTest {

    private static final LocalDateTime OLDEST = LocalDateTime.of(2026, 1, 1, 10, 0, 0);
    private static final LocalDateTime MIDDLE = LocalDateTime.of(2026, 1, 2, 10, 0, 0);
    private static final LocalDateTime NEWEST = LocalDateTime.of(2026, 1, 3, 10, 0, 0);

    private AnnotationConfigWebApplicationContext rootContext;
    private AnnotationConfigWebApplicationContext servletContext;
    private JdbcTemplate jdbcTemplate;
    private MockMvc mockMvc;
    private Long ownerUserId;
    private Long otherOwnerUserId;
    private Long workerUserId;
    private String businessNumberPrefix;

    @BeforeEach
    void setUp() {
        MockServletContext mockServletContext = new MockServletContext();

        rootContext = new AnnotationConfigWebApplicationContext();
        rootContext.setServletContext(mockServletContext);
        rootContext.register(RootConfig.class, FixedAddressGeocoderConfig.class);
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

        jdbcTemplate = new JdbcTemplate(rootContext.getBean(DataSource.class));
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        // 사업자등록번호는 Unique 숫자 10자리이므로 앞 6자리를 실행마다 다르게 만듭니다.
        businessNumberPrefix = String.format(
                "%06d", ThreadLocalRandom.current().nextInt(1_000_000));
        ownerUserId = insertUser(suffix + "o", UserRole.OWNER);
        otherOwnerUserId = insertUser(suffix + "x", UserRole.OWNER);
        workerUserId = insertUser(suffix + "w", UserRole.WORKER);
    }

    @AfterEach
    void tearDown() {
        try {
            for (Long userId : List.of(ownerUserId, otherOwnerUserId, workerUserId)) {
                // 사업장 생성이 고정 QR을 함께 만들므로 qr_tokens를 먼저 지웁니다.
                jdbcTemplate.update(
                        "DELETE FROM qr_tokens WHERE issued_by_user_id = ?", userId);
                jdbcTemplate.update("DELETE FROM workplaces WHERE owner_user_id = ?", userId);
                jdbcTemplate.update("DELETE FROM users WHERE id = ?", userId);
            }
        } finally {
            SecurityContextHolder.clearContext();
            servletContext.close();
            rootContext.close();
        }
    }

    /**
     * 등록한 사업장이 승인된 Page Envelope로 보이고, 오염원은 섞이지 않아야 합니다.
     *
     * <p>{@code DELETED}와 타 OWNER 사업장을 같은 Fixture에 함께 두어야 "안 보인다"가 우연이
     * 아니라 조건 때문임을 확인할 수 있습니다.</p>
     */
    @Test
    @Timeout(60)
    void ownerListsOwnActiveAndInactiveWorkplacesThroughRealFilterChain() throws Exception {
        MockHttpSession session = authenticatedSession(ownerUserId, UserRole.OWNER, "김사장");
        Cookie csrf = csrfCookie(session);

        registerWorkplace(session, csrf, businessNumber(1));
        insertWorkplaceRow(ownerUserId, businessNumber(2), "INACTIVE", MIDDLE);
        insertWorkplaceRow(ownerUserId, businessNumber(3), "DELETED", NEWEST);
        insertWorkplaceRow(otherOwnerUserId, businessNumber(4), "ACTIVE", NEWEST);

        mockMvc.perform(get("/api/workplaces").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.page.totalElements").value(2))
                .andExpect(jsonPath("$.data.page.totalPages").value(1))
                .andExpect(jsonPath("$.data.page.number").value(0))
                .andExpect(jsonPath("$.data.page.size").value(20))
                // 방금 등록한 사업장의 created_at이 가장 최신이라 맨 앞이어야 합니다.
                .andExpect(jsonPath("$.data.content[0].businessRegistrationNumber")
                        .value(businessNumber(1)))
                .andExpect(jsonPath("$.data.content[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.content[0].name").value("강남점"))
                .andExpect(jsonPath("$.data.content[0].phone").value("0212345678"))
                // 저장은 DECIMAL(8,2)이지만 응답 계약은 정수 100입니다.
                .andExpect(jsonPath("$.data.content[0].radiusMeters").value(100))
                .andExpect(jsonPath("$.data.content[0].latitude").doesNotExist())
                .andExpect(jsonPath("$.data.content[0].longitude").doesNotExist())
                .andExpect(jsonPath("$.data.content[1].businessRegistrationNumber")
                        .value(businessNumber(2)))
                .andExpect(jsonPath("$.data.content[1].status").value("INACTIVE"))
                .andExpect(jsonPath("$.data.content[2]").doesNotExist());
    }

    /** 첫 등록이 온보딩 상태를 해소해야 합니다. Session에 캐시하지 않으므로 같은 Session에서 바뀝니다. */
    @Test
    @Timeout(60)
    void firstRegistrationClearsNeedsWorkplaceSetup() throws Exception {
        MockHttpSession session = authenticatedSession(ownerUserId, UserRole.OWNER, "김사장");
        Cookie csrf = csrfCookie(session);

        mockMvc.perform(get("/api/auth/session").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.needsWorkplaceSetup").value(true));

        registerWorkplace(session, csrf, businessNumber(1));

        mockMvc.perform(get("/api/auth/session").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.needsWorkplaceSetup").value(false));
    }

    /**
     * 목록 노출 기준과 온보딩 기준이 서로 다르다는 계약을 고정합니다.
     *
     * <p>목록은 {@code ACTIVE}·{@code INACTIVE}를 보여주지만 {@code needsWorkplaceSetup}은
     * {@code ACTIVE} 0개면 true입니다. 두 기준을 하나로 합치는 회귀를 여기서 막습니다.</p>
     */
    @Test
    @Timeout(60)
    void inactiveOnlyOwnerSeesListItemButStillNeedsWorkplaceSetup() throws Exception {
        MockHttpSession session = authenticatedSession(ownerUserId, UserRole.OWNER, "김사장");
        insertWorkplaceRow(ownerUserId, businessNumber(1), "INACTIVE", MIDDLE);

        mockMvc.perform(get("/api/workplaces").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.page.totalElements").value(1))
                .andExpect(jsonPath("$.data.content[0].status").value("INACTIVE"));

        mockMvc.perform(get("/api/auth/session").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.needsWorkplaceSetup").value(true));
    }

    /** Page가 겹치거나 비지 않고 이어져야 하며, 승인 상한을 넘는 요청은 조용히 낮추지 않고 거절합니다. */
    @Test
    @Timeout(60)
    void pageBoundariesAreEnforcedOverRealRequests() throws Exception {
        MockHttpSession session = authenticatedSession(ownerUserId, UserRole.OWNER, "김사장");
        insertWorkplaceRow(ownerUserId, businessNumber(1), "ACTIVE", NEWEST);
        insertWorkplaceRow(ownerUserId, businessNumber(2), "ACTIVE", MIDDLE);
        insertWorkplaceRow(ownerUserId, businessNumber(3), "ACTIVE", OLDEST);

        expectSinglePageItem(session, 0, businessNumber(1));
        expectSinglePageItem(session, 1, businessNumber(2));
        expectSinglePageItem(session, 2, businessNumber(3));

        mockMvc.perform(get("/api/workplaces").session(session).param("page", "3").param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content").isEmpty())
                .andExpect(jsonPath("$.data.page.totalElements").value(3));

        mockMvc.perform(get("/api/workplaces").session(session).param("size", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    @Timeout(60)
    void workerIsRejectedWithApprovedRoleMismatchEnvelope() throws Exception {
        MockHttpSession session = authenticatedSession(workerUserId, UserRole.WORKER, "김근로");
        insertWorkplaceRow(ownerUserId, businessNumber(1), "ACTIVE", NEWEST);

        mockMvc.perform(get("/api/workplaces").session(session))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ROLE_MISMATCH"));
    }

    /** 조회는 상태를 바꾸지 않아 CSRF 대상이 아니므로 비인증 요청은 Token 없이도 401이어야 합니다. */
    @Test
    @Timeout(60)
    void requestWithoutSessionIsRejectedBySecurityFilterChain() throws Exception {
        mockMvc.perform(get("/api/workplaces"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));
    }

    private void expectSinglePageItem(MockHttpSession session, int page, String businessNumber)
            throws Exception {
        mockMvc.perform(get("/api/workplaces")
                        .session(session)
                        .param("page", String.valueOf(page))
                        .param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.page.number").value(page))
                .andExpect(jsonPath("$.data.page.totalPages").value(3))
                .andExpect(jsonPath("$.data.content[0].businessRegistrationNumber").value(businessNumber))
                .andExpect(jsonPath("$.data.content[1]").doesNotExist());
    }

    private void registerWorkplace(MockHttpSession session, Cookie csrf, String businessNumber)
            throws Exception {
        mockMvc.perform(post("/api/workplaces")
                        .session(session)
                        .cookie(csrf)
                        .header("X-XSRF-TOKEN", csrf.getValue())
                        .contentType(APPLICATION_JSON)
                        .content("{"
                                + "\"businessRegistrationNumber\":\"" + businessNumber + "\","
                                + "\"name\":\"강남점\","
                                + "\"representativeName\":\"김사장\","
                                + "\"roadAddress\":\"서울 강남구 테헤란로 1\","
                                + "\"detailAddress\":\"2층\","
                                + "\"phone\":\"02-1234-5678\""
                                + "}"))
                .andExpect(status().isCreated());
    }

    /** {@code deleted_at}은 {@code ck_workplaces_deleted_at}이 DELETED와 1:1로 묶어 둡니다. */
    private void insertWorkplaceRow(
            Long targetOwnerUserId,
            String businessRegistrationNumber,
            String status,
            LocalDateTime createdAt) {
        Timestamp deletedAt = "DELETED".equals(status) ? Timestamp.valueOf(createdAt) : null;

        jdbcTemplate.update(
                "INSERT INTO workplaces "
                        + "(owner_user_id, business_registration_number, name, representative_name, "
                        + " road_address, detail_address, phone, radius_meters, status, deleted_at, created_at) "
                        + "VALUES (?, ?, '강남점', '김사장', '서울 강남구 테헤란로 1', '2층', '0212345678', "
                        + " 100.00, ?, ?, ?)",
                targetOwnerUserId,
                businessRegistrationNumber,
                status,
                deletedAt,
                Timestamp.valueOf(createdAt));
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

    private Cookie csrfCookie(MockHttpSession session) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/auth/csrf").session(session))
                .andExpect(status().isNoContent())
                .andReturn();
        return result.getResponse().getCookie("XSRF-TOKEN");
    }

    private String businessNumber(int index) {
        return businessNumberPrefix + String.format("%04d", index);
    }

    private Long insertUser(String suffix, UserRole role) {
        String loginId = ("qa145" + suffix).substring(0, Math.min(50, ("qa145" + suffix).length()));
        jdbcTemplate.update(
                "INSERT INTO users (login_id, email, password_hash, name, role) VALUES (?, ?, ?, ?, ?)",
                loginId,
                loginId + "@example.invalid",
                "$2a$10$0000000000000000000000000000000000000000000000000000",
                "테스트사용자",
                role.name());
        return jdbcTemplate.queryForObject(
                "SELECT id FROM users WHERE login_id = ?", Long.class, loginId);
    }

    @Configuration
    @EnableWebMvc
    static class ServletTestConfig {

        @Bean
        WorkplaceController workplaceController(WorkplaceService workplaceService) {
            return new WorkplaceController(workplaceService);
        }

        /** CSRF Token 발급과 온보딩 상태 조회는 실제 인증 Controller가 담당하므로 함께 등록합니다. */
        @Bean
        AuthController authController(
                AuthService authService,
                AuthSessionManager authSessionManager) {
            return new AuthController(authService, authSessionManager);
        }

        @Bean
        CommonExceptionHandler commonExceptionHandler() {
            return new CommonExceptionHandler();
        }
    }
}
