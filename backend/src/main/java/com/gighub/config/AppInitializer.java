package com.gighub.config;

import com.gighub.common.trace.TraceIdFilter;

import java.nio.charset.StandardCharsets;

import javax.servlet.Filter;
import javax.servlet.MultipartConfigElement;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.ServletRegistration;
import javax.servlet.SessionCookieConfig;

import org.springframework.web.filter.CharacterEncodingFilter;
import org.springframework.web.servlet.support.AbstractAnnotationConfigDispatcherServletInitializer;

/**
 * {@code web.xml}을 대신해 Spring Root Context와 DispatcherServlet을 초기화합니다.
 *
 * <p>Tomcat 9가 Servlet 4.0 애플리케이션을 시작할 때 이 클래스를 자동으로 발견합니다. 모든 요청은
 * DispatcherServlet의 {@code /} 매핑을 거치며, 요청과 응답은 UTF-8로 통일합니다.</p>
 */
public class AppInitializer extends AbstractAnnotationConfigDispatcherServletInitializer {

    /** 컨테이너 임시 저장소 상한입니다. 업로드 정책상 실제 허용 크기는 각 검증기가 정한다. */
    private static final long MULTIPART_MAX_FILE_SIZE = 15L * 1024 * 1024;
    private static final long MULTIPART_MAX_REQUEST_SIZE = 16L * 1024 * 1024;

    /**
     * 로컬 HTTP 개발 환경에서 사용할 Session Cookie 범위를 명시합니다.
     *
     * <p>Cookie Domain을 지정하지 않아 {@code localhost} 호스트에만 전송하고, JavaScript에서는
     * Session ID를 읽을 수 없게 합니다. 운영 HTTPS 전환 시 Secure 정책은 배포 설정과 함께
     * 다시 적용해야 합니다.</p>
     *
     * @param servletContext Tomcat이 제공한 Servlet Context
     * @throws ServletException Spring 애플리케이션 초기화 실패 시
     */
    @Override
    public void onStartup(ServletContext servletContext) throws ServletException {
        SessionCookieConfig sessionCookie = servletContext.getSessionCookieConfig();
        sessionCookie.setHttpOnly(true);
        sessionCookie.setSecure(false);
        sessionCookie.setPath("/");
        sessionCookie.setDomain(null);

        super.onStartup(servletContext);
    }

    /**
     * Service와 영속성 Bean이 들어갈 Root Context 설정을 반환합니다.
     *
     * @return Root Context 설정 클래스 목록
     */
    @Override
    protected Class<?>[] getRootConfigClasses() {
        return new Class<?>[]{RootConfig.class};
    }

    /**
     * Controller와 Spring MVC Bean이 들어갈 Servlet Context 설정을 반환합니다.
     *
     * @return Servlet Context 설정 클래스 목록
     */
    @Override
    protected Class<?>[] getServletConfigClasses() {
        return new Class<?>[]{WebMvcConfig.class};
    }

    /**
     * DispatcherServlet이 처리할 기본 URL 범위를 지정합니다.
     *
     * @return Servlet 매핑 목록
     */
    @Override
    protected String[] getServletMappings() {
        return new String[]{"/"};
    }

    /**
     * 한글 요청 본문과 JSON 응답의 인코딩을 UTF-8로 강제합니다.
     *
     * @return DispatcherServlet 앞에서 실행할 Filter 목록
     */
    @Override
    protected Filter[] getServletFilters() {
        CharacterEncodingFilter encodingFilter = new CharacterEncodingFilter();
        encodingFilter.setEncoding(StandardCharsets.UTF_8.name());
        encodingFilter.setForceEncoding(true);
        return new Filter[]{encodingFilter, new TraceIdFilter()};
    }

    /**
     * DispatcherServlet에 Multipart 처리를 등록합니다. 컨테이너 상한은 업로드 정책의 실제
     * 허용 크기(예: 보건증 10 MiB)보다 넉넉히 잡아, 정확한 거부 사유는 각 도메인 검증기가
     * 안전한 오류 응답으로 돌려주게 한다.
     *
     * @param registration DispatcherServlet의 Servlet 등록 정보
     */
    @Override
    protected void customizeRegistration(ServletRegistration.Dynamic registration) {
        registration.setMultipartConfig(new MultipartConfigElement(
                "", MULTIPART_MAX_FILE_SIZE, MULTIPART_MAX_REQUEST_SIZE, 0));
    }
}

