package com.gighub.settlement.config;

import com.gighub.settlement.service.SettlementScheduledPayoutScheduler;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.scheduling.support.PeriodicTrigger;
import org.springframework.stereotype.Component;

/**
 * #172 Scheduler 실행 주기를 {@link SettlementSchedulerProperties}의 외부 설정값으로 등록한다.
 *
 * <p>이 프로젝트는 {@code @Value}/{@code ${...}} Placeholder를 해석하는
 * {@code PropertySourcesPlaceholderConfigurer}를 두지 않으므로, {@code @Scheduled}에 문자열
 * Placeholder를 직접 쓸 수 없다. 대신 {@link SchedulingConfigurer}로 기동 시점에 이미 읽어들인
 * {@link SettlementSchedulerProperties} 값으로 {@link PeriodicTrigger}를 만들어 등록한다.</p>
 *
 * <p>{@code @EnableScheduling}은 {@code RootConfig}에 이미 선언돼 있고, Spring은 Bean
 * 종류와 무관하게 {@link SchedulingConfigurer} 구현체를 모두 찾아 호출하므로 별도 Import 없이
 * 이 Component만으로 연결된다.</p>
 */
@Component
@RequiredArgsConstructor
public class SettlementSchedulingConfigurer implements SchedulingConfigurer {

    private final SettlementScheduledPayoutScheduler scheduler;
    private final SettlementSchedulerProperties properties;

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        PeriodicTrigger trigger = new PeriodicTrigger(properties.getFixedDelay().toMillis());
        trigger.setInitialDelay(properties.getInitialDelay().toMillis());
        registrar.addTriggerTask(scheduler::runOnce, trigger);
    }
}
