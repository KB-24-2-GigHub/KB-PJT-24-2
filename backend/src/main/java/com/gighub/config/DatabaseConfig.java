package com.gighub.config;

import javax.sql.DataSource;

import com.zaxxer.hikari.HikariDataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.PropertySource;
import org.springframework.core.env.Environment;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 외부 properties 파일에서 로컬 MySQL 접속 정보를 읽어 영속성 기반 Bean을 구성합니다.
 *
 * <p>설정 파일 위치는 JVM 시스템 속성 {@code gighub.database.config}로 전달합니다. 실제 접속 정보는
 * WAR와 Git에 포함하지 않으며, 스키마 변경은 애플리케이션이 아닌 Flyway가 담당합니다.</p>
 */
@Configuration
@EnableTransactionManagement
@PropertySource(value = "file:${gighub.database.config}", encoding = "UTF-8")@MapperScan(basePackages = {
        "com.gighub.attendance.mapper",
        "com.gighub.auth.mapper",
        "com.gighub.member.mapper",
        "com.gighub.wallet.mapper",
        "com.gighub.work.mapper",
        "com.gighub.contract.mapper",
        "com.gighub.document.mapper",
        "com.gighub.idempotency.mapper",
        "com.gighub.invitation.mapper",
        "com.gighub.notification.mapper",
        "com.gighub.badge.mapper",
        "com.gighub.bank.mapper",
        "com.gighub.settlement.mapper",
        "com.gighub.workplace.mapper"
})
public class DatabaseConfig {

    private static final String MAPPER_LOCATIONS = "classpath*:mappers/**/*.xml";

    /**
     * HikariCP 기반 애플리케이션 DataSource를 구성합니다.
     *
     * @param environment 외부 properties가 포함된 Spring 환경
     * @return MySQL 연결용 DataSource
     */
    @Bean(destroyMethod = "close")
    public DataSource dataSource(Environment environment) {
        HikariDataSource dataSource = new HikariDataSource();
        dataSource.setPoolName("gig-hub-database");
        dataSource.setDriverClassName(
                environment.getRequiredProperty("database.driver-class-name")
        );
        dataSource.setJdbcUrl(environment.getRequiredProperty("database.jdbc-url"));
        dataSource.setUsername(environment.getRequiredProperty("database.username"));
        dataSource.setPassword(environment.getRequiredProperty("database.password"));
        dataSource.setMaximumPoolSize(
                environment.getProperty("database.pool.maximum-pool-size", Integer.class, 10)
        );
        dataSource.setMinimumIdle(
                environment.getProperty("database.pool.minimum-idle", Integer.class, 2)
        );
        dataSource.setConnectionTimeout(
                environment.getProperty("database.pool.connection-timeout-ms", Long.class, 30_000L)
        );
        dataSource.setValidationTimeout(
                environment.getProperty("database.pool.validation-timeout-ms", Long.class, 5_000L)
        );
        return dataSource;
    }

    /**
     * MyBatis가 Mapper XML을 실행할 때 사용할 SqlSessionFactory를 구성합니다.
     *
     * @param dataSource 애플리케이션 DataSource
     * @return MyBatis SqlSessionFactory
     * @throws Exception MyBatis 설정 생성 실패 시
     */
    @Bean
    public SqlSessionFactory sqlSessionFactory(DataSource dataSource) throws Exception {
        org.apache.ibatis.session.Configuration mybatisConfiguration =
                new org.apache.ibatis.session.Configuration();
        mybatisConfiguration.setMapUnderscoreToCamelCase(true);

        SqlSessionFactoryBean factoryBean = new SqlSessionFactoryBean();
        factoryBean.setDataSource(dataSource);
        factoryBean.setConfiguration(mybatisConfiguration);
        factoryBean.setMapperLocations(
                new PathMatchingResourcePatternResolver().getResources(MAPPER_LOCATIONS)
        );
        return factoryBean.getObject();
    }

    /**
     * Service 계층의 Spring Transaction을 JDBC DataSource에 연결합니다.
     *
     * @param dataSource 애플리케이션 DataSource
     * @return JDBC Transaction Manager
     */
    @Bean
    public PlatformTransactionManager transactionManager(DataSource dataSource) {
        return new DataSourceTransactionManager(dataSource);
    }

    /**
     * 트랜잭션 경계를 메서드보다 좁게 잡아야 하는 Service를 위한 프로그래밍 방식 Template입니다.
     *
     * <p>외부 호출을 트랜잭션 밖에 두고 저장만 묶으려면 경계가 메서드 단위인 {@code
     * @Transactional}로는 부족합니다. 같은 Bean 안에서 나눈 메서드는 Proxy를 거치지 않아
     * 애노테이션이 적용되지 않습니다.</p>
     *
     * @param transactionManager JDBC Transaction Manager
     * @return 공유 Transaction Template
     */
    @Bean
    public TransactionTemplate transactionTemplate(PlatformTransactionManager transactionManager) {
        return new TransactionTemplate(transactionManager);
    }
}
