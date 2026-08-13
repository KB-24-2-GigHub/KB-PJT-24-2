package com.gighub.workplace;

import java.math.BigDecimal;
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
import com.gighub.member.domain.UserRole;
import com.gighub.workplace.controller.WorkplaceController;
import com.gighub.workplace.geocoding.FixedAddressGeocoderConfig;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 실제 Security Filter Chain과 MySQL Head Schema로 사업장 등록 계약을 검증합니다.
 *
 * <p>계층별 Test는 각각 가짜 협력자를 쓰므로 Security 인가, CSRF, MyBatis Mapping, DB
 * 제약이 한 요청에서 함께 동작하는지는 여기서만 확인할 수 있습니다.</p>
 */
@Tag("database")
class WorkplaceCreateFlowIntegrationTest {

    /** 주소 변환을 대신하는 고정 좌표입니다. 저장된 값이 요청이 아닌 변환 결과인지 확인합니다. */
    private static final BigDecimal GEOCODED_LATITUDE = FixedAddressGeocoderConfig.LATITUDE;
    private static final BigDecimal GEOCODED_LONGITUDE = FixedAddressGeocoderConfig.LONGITUDE;

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

    @Test
    @Timeout(60)
    void ownerRegistersWorkplaceWithContractValuesThroughRealFilterChain() throws Exception {
        MockHttpSession session = authenticatedSession(ownerUserId, UserRole.OWNER, "김사장");
        Cookie csrf = csrfCookie(session);

        mockMvc.perform(post("/api/workplaces")
                        .session(session)
                        .cookie(csrf)
                        .header("X-XSRF-TOKEN", csrf.getValue())
                        .contentType(APPLICATION_JSON)
                        .content(body(businessNumber(1), "")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.workplaceId").isNumber());

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT * FROM workplaces WHERE business_registration_number = ?",
                businessNumber(1));

        // 소유자는 Body가 아니라 Session Principal에서 결정돼야 합니다.
        assertEquals(ownerUserId, ((Number) row.get("owner_user_id")).longValue());
        assertEquals(businessNumber(1), row.get("business_registration_number"));
        // 전화번호는 승인된 정규화 대상이라 화면 표시 형식으로 보내도 숫자만 저장돼야 합니다.
        assertEquals("0212345678", row.get("phone"));
        assertEquals("강남점", row.get("name"));
        assertEquals("서울 강남구 테헤란로 1", row.get("road_address"));
        // 좌표는 요청이 아니라 서버의 주소 변환 결과로 저장돼야 합니다(SPEC-343-01).
        assertEquals(0, GEOCODED_LATITUDE.compareTo((BigDecimal) row.get("latitude")));
        assertEquals(0, GEOCODED_LONGITUDE.compareTo((BigDecimal) row.get("longitude")));
        // 반경과 최초 상태는 요청이 정할 수 없는 계약값입니다.
        assertEquals(0, new BigDecimal("100.00").compareTo((BigDecimal) row.get("radius_meters")));
        assertEquals("ACTIVE", row.get("status"));
        assertNull(row.get("deleted_at"));
    }

    @Test
    @Timeout(60)
    void workerIsRejectedWithApprovedRoleMismatchEnvelope() throws Exception {
        MockHttpSession session = authenticatedSession(workerUserId, UserRole.WORKER, "김근로");
        Cookie csrf = csrfCookie(session);

        mockMvc.perform(post("/api/workplaces")
                        .session(session)
                        .cookie(csrf)
                        .header("X-XSRF-TOKEN", csrf.getValue())
                        .contentType(APPLICATION_JSON)
                        .content(body(businessNumber(2), "")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ROLE_MISMATCH"));

        assertEquals(0, countWorkplaces(businessNumber(2)));
    }

    /**
     * 인증 Session이 없는 요청을 거절하는지 검증합니다.
     *
     * <p>CSRF Filter가 인가 판정보다 먼저 동작하므로 Token까지 갖춘 요청으로 확인해야
     * 401 경계가 드러납니다. Token이 아예 없으면 인증 여부와 무관하게 403입니다.</p>
     */
    @Test
    @Timeout(60)
    void requestWithoutSessionIsRejectedBySecurityFilterChain() throws Exception {
        Cookie csrf = anonymousCsrfCookie();

        mockMvc.perform(post("/api/workplaces")
                        .cookie(csrf)
                        .header("X-XSRF-TOKEN", csrf.getValue())
                        .contentType(APPLICATION_JSON)
                        .content(body(businessNumber(3), "")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));

        assertEquals(0, countWorkplaces(businessNumber(3)));
    }

    /** CSRF Token 없이 상태를 바꾸는 요청은 인증 Session이 있어도 통과하면 안 됩니다. */
    @Test
    @Timeout(60)
    void requestWithoutCsrfTokenIsRejected() throws Exception {
        MockHttpSession session = authenticatedSession(ownerUserId, UserRole.OWNER, "김사장");

        mockMvc.perform(post("/api/workplaces")
                        .session(session)
                        .contentType(APPLICATION_JSON)
                        .content(body(businessNumber(4), "")))
                .andExpect(status().isForbidden());

        assertEquals(0, countWorkplaces(businessNumber(4)));
    }

    @Test
    @Timeout(60)
    void duplicateBusinessNumberAcrossOwnersUsesApprovedConflict() throws Exception {
        MockHttpSession ownerSession = authenticatedSession(ownerUserId, UserRole.OWNER, "김사장");
        Cookie ownerCsrf = csrfCookie(ownerSession);
        mockMvc.perform(post("/api/workplaces")
                        .session(ownerSession)
                        .cookie(ownerCsrf)
                        .header("X-XSRF-TOKEN", ownerCsrf.getValue())
                        .contentType(APPLICATION_JSON)
                        .content(body(businessNumber(5), "")))
                .andExpect(status().isCreated());

        MockHttpSession otherSession =
                authenticatedSession(otherOwnerUserId, UserRole.OWNER, "이사장");
        Cookie otherCsrf = csrfCookie(otherSession);
        mockMvc.perform(post("/api/workplaces")
                        .session(otherSession)
                        .cookie(otherCsrf)
                        .header("X-XSRF-TOKEN", otherCsrf.getValue())
                        .contentType(APPLICATION_JSON)
                        .content(body(businessNumber(5), "")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));

        assertEquals(1, countWorkplaces(businessNumber(5)));
    }

    @Test
    @Timeout(60)
    void unapprovedRadiusAndClientCoordinatesAreRejectedBeforeStorage() throws Exception {
        MockHttpSession session = authenticatedSession(ownerUserId, UserRole.OWNER, "김사장");
        Cookie csrf = csrfCookie(session);

        mockMvc.perform(post("/api/workplaces")
                        .session(session)
                        .cookie(csrf)
                        .header("X-XSRF-TOKEN", csrf.getValue())
                        .contentType(APPLICATION_JSON)
                        .content(body(businessNumber(6), "\"radiusM\":500,")))
                .andExpect(status().isBadRequest())
                // 미승인 필드는 @Valid가 아니라 역직렬화 단계에서 끊기므로 Code를 함께 고정합니다.
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        mockMvc.perform(post("/api/workplaces")
                        .session(session)
                        .cookie(csrf)
                        .header("X-XSRF-TOKEN", csrf.getValue())
                        .contentType(APPLICATION_JSON)
                        .content(body(
                                businessNumber(7),
                                "\"latitude\":37.1234567,\"longitude\":127.1234567,")))
                .andExpect(status().isBadRequest())
                // 좌표는 서버가 주소로 확정하므로 요청 필드로 받지 않습니다(SPEC-343-01).
                // 미승인 필드라 @Valid 이전 역직렬화 단계에서 끊깁니다.
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        assertEquals(0, countWorkplaces(businessNumber(6)));
        assertEquals(0, countWorkplaces(businessNumber(7)));
    }

    /** 같은 사업자등록번호로 동시에 요청해도 한 건만 만들어져야 합니다. */
    @Test
    @Timeout(60)
    void concurrentRegistrationCreatesSingleWorkplace() throws Exception {
        MockHttpSession session = authenticatedSession(ownerUserId, UserRole.OWNER, "김사장");
        Cookie csrf = csrfCookie(session);
        String businessNumber = businessNumber(8);

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Callable<Integer> attempt = () -> {
                start.await();
                return mockMvc.perform(post("/api/workplaces")
                                .session(session)
                                .cookie(csrf)
                                .header("X-XSRF-TOKEN", csrf.getValue())
                                .contentType(APPLICATION_JSON)
                                .content(body(businessNumber, "")))
                        .andReturn()
                        .getResponse()
                        .getStatus();
            };

            List<Future<Integer>> results =
                    List.of(executor.submit(attempt), executor.submit(attempt));
            start.countDown();

            int created = 0;
            int conflict = 0;
            for (Future<Integer> result : results) {
                int status = result.get(30, TimeUnit.SECONDS);
                if (status == 201) {
                    created++;
                } else if (status == 409) {
                    conflict++;
                }
            }

            assertEquals(1, created, "동시 요청에서 한 건만 생성돼야 합니다.");
            assertEquals(1, conflict, "나머지 요청은 승인된 충돌로 끝나야 합니다.");
            assertEquals(1, countWorkplaces(businessNumber));
        } finally {
            executor.shutdownNow();
        }
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

    private int countWorkplaces(String businessRegistrationNumber) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM workplaces WHERE business_registration_number = ?",
                Integer.class,
                businessRegistrationNumber);
    }

    private String businessNumber(int index) {
        return businessNumberPrefix + String.format("%04d", index);
    }

    private String body(String businessRegistrationNumber, String extraFields) {
        return "{"
                + "\"businessRegistrationNumber\":\"" + businessRegistrationNumber + "\","
                + "\"name\":\"  강남점  \","
                + "\"representativeName\":\"김사장\","
                + "\"roadAddress\":\"서울 강남구 테헤란로 1\","
                + "\"detailAddress\":\"2층\","
                + extraFields
                + "\"phone\":\"02-1234-5678\""
                + "}";
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

        /** CSRF Token 발급은 실제 인증 Controller가 담당하므로 함께 등록합니다. */
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
