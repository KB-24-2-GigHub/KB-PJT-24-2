package com.gighub.auth.security;

import java.util.Arrays;

import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;

import com.gighub.config.RootConfig;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SecurityConfigTest {

    @Test
    void exposesBcryptPasswordEncoder() {
        PasswordEncoder encoder = new SecurityConfig(new MockEnvironment()).passwordEncoder();

        assertInstanceOf(BCryptPasswordEncoder.class, encoder);
        String hash = encoder.encode("password1234");
        assertTrue(encoder.matches("password1234", hash));
    }

    @Test
    void corsContractMatchesApprovedLocalBoundary() {
        CorsConfigurationSource source = new SecurityConfig(new MockEnvironment()).corsConfigurationSource();
        HttpServletRequest request = new MockHttpServletRequest("OPTIONS", "/api/auth/login");
        CorsConfiguration configuration = source.getCorsConfiguration(request);

        assertNotNull(configuration);
        assertEquals(Arrays.asList("http://localhost:5173"), configuration.getAllowedOrigins());
        assertEquals(Arrays.asList("Accept", "Content-Type", "X-XSRF-TOKEN", "Idempotency-Key"),
                configuration.getAllowedHeaders());
        assertEquals(Arrays.asList("Location", "Idempotency-Replayed"), configuration.getExposedHeaders());
        assertEquals(Boolean.TRUE, configuration.getAllowCredentials());
    }

    @Test
    void corsUsesConfiguredOriginsWhenPropertyIsSet() {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty(
                "cors.allowed-origins",
                "https://gighub.store,https://www.gighub.store");

        CorsConfigurationSource source = new SecurityConfig(environment).corsConfigurationSource();
        CorsConfiguration configuration =
                source.getCorsConfiguration(new MockHttpServletRequest("OPTIONS", "/api/auth/login"));

        assertNotNull(configuration);
        assertEquals(
                Arrays.asList("https://gighub.store", "https://www.gighub.store"),
                configuration.getAllowedOrigins());
    }

    @Test
    void corsTrimsWhitespaceAroundConfiguredOrigins() {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("cors.allowed-origins", " https://gighub.store , https://a.example ");

        CorsConfigurationSource source = new SecurityConfig(environment).corsConfigurationSource();
        CorsConfiguration configuration =
                source.getCorsConfiguration(new MockHttpServletRequest("OPTIONS", "/api/auth/login"));

        assertNotNull(configuration);
        assertEquals(
                Arrays.asList("https://gighub.store", "https://a.example"),
                configuration.getAllowedOrigins());
    }

    @Test
    void csrfCookieHasNoDomainWhenPropertyIsAbsent() {
        CsrfTokenRepository repository = new SecurityConfig(new MockEnvironment()).csrfTokenRepository();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/auth/csrf");
        MockHttpServletResponse response = new MockHttpServletResponse();

        CsrfToken token = repository.generateToken(request);
        repository.saveToken(token, request, response);

        Cookie cookie = response.getCookie("XSRF-TOKEN");
        assertNotNull(cookie);
        assertNull(cookie.getDomain());
    }

    @Test
    void csrfCookieUsesConfiguredDomain() {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("security.cookie.domain", "gighub.store");

        CsrfTokenRepository repository = new SecurityConfig(environment).csrfTokenRepository();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/auth/csrf");
        MockHttpServletResponse response = new MockHttpServletResponse();

        CsrfToken token = repository.generateToken(request);
        repository.saveToken(token, request, response);

        Cookie cookie = response.getCookie("XSRF-TOKEN");
        assertNotNull(cookie);
        assertEquals("gighub.store", cookie.getDomain());
    }

    /**
     * 배포에서는 nginx 가 TLS 를 종료하므로 Tomcat 에 도달하는 요청은 평문이다.
     * setSecure 를 명시하지 않아야 CookieCsrfTokenRepository 가 request.isSecure() 를
     * 따르고, RemoteIpValve 가 X-Forwarded-Proto 를 해석한 환경에서만 Secure 가 붙는다.
     */
    @Test
    void csrfCookieSecureFollowsRequestSecurity() {
        CsrfTokenRepository repository = new SecurityConfig(new MockEnvironment()).csrfTokenRepository();

        MockHttpServletRequest plainRequest = new MockHttpServletRequest("GET", "/api/auth/csrf");
        MockHttpServletResponse plainResponse = new MockHttpServletResponse();
        repository.saveToken(repository.generateToken(plainRequest), plainRequest, plainResponse);

        MockHttpServletRequest secureRequest = new MockHttpServletRequest("GET", "/api/auth/csrf");
        secureRequest.setSecure(true);
        MockHttpServletResponse secureResponse = new MockHttpServletResponse();
        repository.saveToken(repository.generateToken(secureRequest), secureRequest, secureResponse);

        assertEquals(false, plainResponse.getCookie("XSRF-TOKEN").getSecure());
        assertEquals(true, secureResponse.getCookie("XSRF-TOKEN").getSecure());
    }

    @Test
    void rootConfigImportsSecurityConfigExplicitly() {
        Import importConfig = RootConfig.class.getAnnotation(Import.class);

        assertNotNull(importConfig);
        assertTrue(Arrays.asList(importConfig.value()).contains(SecurityConfig.class));
    }
}
