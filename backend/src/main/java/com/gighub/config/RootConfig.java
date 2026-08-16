package com.gighub.config;

import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.stereotype.Controller;

import com.gighub.auth.security.SecurityConfig;
import com.gighub.settlement.config.SettlementTaskSchedulerConfig;

/**
 * 웹 계층을 제외한 애플리케이션 공통 Bean을 관리하는 Root Context 설정입니다.
 *
 * <p>Service와 공통 Component는 이 Context에서 관리합니다. Controller는 DispatcherServlet이
 * 사용하는 Servlet Context에서만 생성하여 같은 Bean이 중복 등록되지 않게 합니다.</p>
 *
 * <p>MySQL 연결과 MyBatis 영속성 Bean은 {@link DatabaseConfig}에 분리해 관리합니다.</p>
 */
@Configuration
@EnableScheduling
@Import({DatabaseConfig.class, SecurityConfig.class, SettlementTaskSchedulerConfig.class})
@ComponentScan(
        basePackages = "com.gighub",
        excludeFilters = {
            @ComponentScan.Filter(type = FilterType.ANNOTATION, classes = Controller.class),
            @ComponentScan.Filter(type = FilterType.ANNOTATION, classes = Configuration.class)
        }
)
public class RootConfig {
    // 공통 Component 검색 경계와 분리된 인프라 설정 조합만 선언합니다.
}
