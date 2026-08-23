package com.gighub.config;

import com.fasterxml.classmate.TypeResolver;
import com.gighub.common.api.ApiErrorResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.Authentication;
import springfox.documentation.builders.ApiInfoBuilder;
import springfox.documentation.builders.PathSelectors;
import springfox.documentation.builders.RequestHandlerSelectors;
import springfox.documentation.oas.annotations.EnableOpenApi;
import springfox.documentation.service.ApiInfo;
import springfox.documentation.spi.DocumentationType;
import springfox.documentation.spring.web.plugins.Docket;


import java.security.Principal;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

@Configuration
@EnableOpenApi
public class SwaggerConfig {

    // 정본 명세 릴리스와의 일치는 저장소 Guardrail에서 함께 검증한다.
    static final String SPEC_RELEASE_VERSION = "9.0.0";

    private final TypeResolver typeResolver = new TypeResolver();

    @Bean
    public Docket api() {
        return new Docket(DocumentationType.OAS_30)
                // 인증 주체는 Controller Method 인자로 주입될 뿐 요청 Parameter가 아니므로 문서에서 제외한다.
                .ignoredParameterTypes(HttpSession.class, HttpServletRequest.class, HttpServletResponse.class,
                        Authentication.class, Principal.class)
                .useDefaultResponseMessages(false) // 기본 응답 메시지(200, 401 등) 자동 추가 끄기
                // 오류는 CommonExceptionHandler가 공통 Envelope로 반환하므로 Controller 반환 타입에
                // 나타나지 않는다. 탐색자가 오류 계약을 볼 수 있도록 Schema를 명시 등록한다(#123).
                .additionalModels(typeResolver.resolve(ApiErrorResponse.class))
                .select()
                .apis(RequestHandlerSelectors.basePackage("com.gighub")) // API 컨트롤러가 있는 최상위 패키지
                .paths(PathSelectors.ant("/api/**")) // /api/ 로 시작하는 주소만 문서화
                .build()
                .pathMapping("/")
                .apiInfo(apiInfo());
    }

    @Bean
    public SwaggerPageParameterContractFilter swaggerPageParameterContractFilter() {
        return new SwaggerPageParameterContractFilter();
    }

    private ApiInfo apiInfo(){
        return new ApiInfoBuilder()
                .title("GigHub API 명세서")
                .description("GigHub API 문서입니다.")
                .version(SPEC_RELEASE_VERSION)
                .build();
    }
}
