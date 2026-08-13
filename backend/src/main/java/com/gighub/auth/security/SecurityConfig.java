package com.gighub.auth.security;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import com.gighub.member.domain.UserRole;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/** Tomcat Root Context에서 Session·CSRF·CORS 인증 경계를 구성합니다. */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /** 두 Key 모두 미설정이 로컬 개발 기본값이며, 그때 현재 동작이 그대로 유지된다. */
    private static final String ALLOWED_ORIGINS_KEY = "cors.allowed-origins";
    private static final String COOKIE_DOMAIN_KEY = "security.cookie.domain";
    private static final String DEFAULT_ALLOWED_ORIGIN = "http://localhost:5173";
    private static final String[] PUBLIC_GET_PATHS = {
        "/api/auth/csrf",
        "/api/auth/session",
        "/api/auth/login-id-availability",
        "/api/auth/email-availability",
        "/api/health/**",
        "/swagger-ui.html",
        "/swagger-ui/**",
        "/webjars/**",
        "/v3/api-docs/**",
        "/swagger-resources",
        "/swagger-resources/**",
        "/configuration/ui",
        "/configuration/security"
    };
    private static final String[] PUBLIC_POST_PATHS = {
        "/api/auth/signup",
        "/api/auth/login",
        "/api/auth/password-reset/requests",
        "/api/auth/password-reset/confirmations"
    };
    private static final String LOCAL_TEST_LOGIN_PATH = "/api/test-login/**";
    // 지갑 충전은 API_SPEC에서 OWNER 전용 Operation이다.
    private static final String FUNDING_ORDERS_PATH = "/api/wallet/funding-orders";

    private final Environment environment;

    public SecurityConfig(Environment environment) {
        this.environment = environment;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public JsonAuthenticationEntryPoint jsonAuthenticationEntryPoint() {
        return new JsonAuthenticationEntryPoint();
    }

    @Bean
    public JsonAccessDeniedHandler jsonAccessDeniedHandler() {
        return new JsonAccessDeniedHandler();
    }

    @Bean
    public CsrfTokenRepository csrfTokenRepository() {
        CookieCsrfTokenRepository repository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        repository.setCookiePath("/");

        // Frontend Origin과 API Origin이 다른 배포에서는 상위 도메인 Cookie여야
        // Frontend JavaScript가 XSRF-TOKEN을 읽어 헤더에 실을 수 있다.
        // 미설정 로컬은 host-only를 유지한다.
        String cookieDomain = environment.getProperty(COOKIE_DOMAIN_KEY);
        if (cookieDomain != null && !cookieDomain.isBlank()) {
            repository.setCookieDomain(cookieDomain.trim());
        }

        // setSecure를 호출하지 않는다. null이면 request.isSecure()를 따르므로
        // RemoteIpValve가 X-Forwarded-Proto를 해석한 배포에서만 Secure가 붙는다.
        return repository;
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(allowedOrigins());
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Accept", "Content-Type", "X-XSRF-TOKEN", "Idempotency-Key"));
        configuration.setExposedHeaders(List.of("Location", "Idempotency-Replayed"));
        configuration.setAllowCredentials(true);
        configuration.setMaxAge(3_600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", configuration);
        return source;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            CsrfTokenRepository csrfTokenRepository,
            CorsConfigurationSource corsConfigurationSource,
            JsonAuthenticationEntryPoint authenticationEntryPoint,
            JsonAccessDeniedHandler accessDeniedHandler) throws Exception {
        CsrfTokenRequestAttributeHandler csrfRequestHandler = new CsrfTokenRequestAttributeHandler();
        csrfRequestHandler.setCsrfRequestAttributeName("_csrf");

        http
                .cors(cors -> cors.configurationSource(corsConfigurationSource))
                .csrf(csrf -> csrf
                        .csrfTokenRepository(csrfTokenRepository)
                        .csrfTokenRequestHandler(csrfRequestHandler))
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .authorizeHttpRequests(authorize -> {
                    authorize.requestMatchers(matchers(HttpMethod.GET, PUBLIC_GET_PATHS)).permitAll();
                    authorize.requestMatchers(matchers(HttpMethod.POST, PUBLIC_POST_PATHS)).permitAll();
                    if (environment.acceptsProfiles(Profiles.of("local"))) {
                        authorize.requestMatchers(matchers(HttpMethod.GET, LOCAL_TEST_LOGIN_PATH)).permitAll();
                    }
                    authorize.requestMatchers(matchers(HttpMethod.POST, FUNDING_ORDERS_PATH))
                            .hasRole(UserRole.OWNER.name());
                    authorize.anyRequest().authenticated();
                });

        return http.build();
    }

    /**
     * 배포 Origin은 외부 properties로 주입하고, 미설정 시 로컬 개발 Origin을 유지합니다.
     *
     * @return Credentials 포함 요청을 허용할 브라우저 Origin 목록
     */
    private List<String> allowedOrigins() {
        String configured = environment.getProperty(ALLOWED_ORIGINS_KEY);

        if (configured == null || configured.isBlank()) {
            return List.of(DEFAULT_ALLOWED_ORIGIN);
        }

        return Arrays.stream(configured.split(","))
                .map(String::trim)
                .filter(origin -> !origin.isEmpty())
                .collect(Collectors.toList());
    }

    private static RequestMatcher[] matchers(HttpMethod method, String... patterns) {
        return Arrays.stream(patterns)
                .map(pattern -> new AntPathRequestMatcher(pattern, method.name()))
                .toArray(RequestMatcher[]::new);
    }
}
