package com.gighub.auth.security;

import java.util.Arrays;
import java.util.List;

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

    private static final String LOCAL_ORIGIN = "http://localhost:5173";
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
        return repository;
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(List.of(LOCAL_ORIGIN));
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
                // 계약서·보건증은 같은 Origin 화면의 iframe에서만 미리보기한다.
                // 외부 Origin의 프레이밍은 계속 차단하되 기본 DENY로 내부 미리보기까지 막지 않는다.
                .headers(headers -> headers.frameOptions(frameOptions -> frameOptions.sameOrigin()))
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

    private static RequestMatcher[] matchers(HttpMethod method, String... patterns) {
        return Arrays.stream(patterns)
                .map(pattern -> new AntPathRequestMatcher(pattern, method.name()))
                .toArray(RequestMatcher[]::new);
    }
}
