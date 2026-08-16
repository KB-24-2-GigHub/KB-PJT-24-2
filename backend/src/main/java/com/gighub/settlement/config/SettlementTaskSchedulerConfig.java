package com.gighub.settlement.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/** 지급과 분쟁 후보 조회가 서로의 실행 주기를 막지 않도록 최소 두 Thread를 제공합니다. */
@Configuration
public class SettlementTaskSchedulerConfig {

    @Bean
    public TaskScheduler financialTaskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(2);
        scheduler.setThreadNamePrefix("financial-scheduler-");
        scheduler.setWaitForTasksToCompleteOnShutdown(false);
        return scheduler;
    }
}
