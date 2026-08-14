package com.gighub.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.stereotype.Controller;
import org.springframework.web.multipart.MultipartResolver;
import org.springframework.web.multipart.support.StandardServletMultipartResolver;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

/**
 * DispatcherServlet이 사용하는 Spring MVC 전용 설정입니다.
 *
 * <p>{@link Controller} 계열 Bean만 검색하여 Root Context와 책임을 분리합니다. Jackson이
 * classpath에 있으므로 기본 JSON MessageConverter는 Spring MVC가 등록합니다.</p>
 *
 * <p>승인된 공통 오류·응답 규격의 Validator와 예외 통합은 별도 구현으로 남아 있습니다.
 * CORS는 Security Filter보다 먼저 처리돼야 하므로 Root Context의 Security 설정이 단일하게
 * 소유합니다.</p>
 */
@Configuration
@EnableWebMvc
@Import(SwaggerConfig.class)
@ComponentScan(
        basePackages = "com.gighub",
        includeFilters = @ComponentScan.Filter(
                type = FilterType.ANNOTATION,
                classes = Controller.class
        ),
        useDefaultFilters = false
)
public class WebMvcConfig implements WebMvcConfigurer {
    // 기본 MVC 설정으로 시작하고 실제 요구가 생길 때 필요한 메서드만 재정의합니다.

    @Override
    public void extendMessageConverters(List<HttpMessageConverter<?>> converters) {
        converters.stream()
                .filter(MappingJackson2HttpMessageConverter.class::isInstance)
                .map(MappingJackson2HttpMessageConverter.class::cast)
                .forEach(converter -> ApiJsonMapper.configure(converter.getObjectMapper()));
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // Swagger UI 화면 정적 리소스 매핑 허용
        registry.addResourceHandler("/swagger-ui/**")
                .addResourceLocations("classpath:/META-INF/resources/webjars/springfox-swagger-ui/");

        registry.addResourceHandler("/webjars/**")
                .addResourceLocations("classpath:/META-INF/resources/webjars/");
    }

    /**
     * DispatcherServlet이 이 정확한 Bean 이름으로 Multipart 요청 여부를 판단한다. Servlet
     * Container의 {@code MultipartConfigElement}(AppInitializer)에 실제 처리를 위임한다.
     */
    @Bean
    public MultipartResolver multipartResolver() {
        return new StandardServletMultipartResolver();
    }
}

