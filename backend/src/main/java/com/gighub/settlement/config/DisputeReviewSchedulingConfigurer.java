package com.gighub.settlement.config;

import com.gighub.settlement.service.DisputeReviewScheduler;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.scheduling.support.PeriodicTrigger;
import org.springframework.stereotype.Component;

/** 짧은 후보 조회만 공용 Scheduler에서 실행하고 외부 호출은 전용 Worker로 넘깁니다. */
@Component
@RequiredArgsConstructor
public class DisputeReviewSchedulingConfigurer implements SchedulingConfigurer {

    private final DisputeReviewScheduler scheduler;
    private final DisputeReviewProperties properties;
    private final TaskScheduler taskScheduler;

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        registrar.setTaskScheduler(taskScheduler);
        PeriodicTrigger trigger = new PeriodicTrigger(properties.getFixedDelay().toMillis());
        trigger.setInitialDelay(properties.getInitialDelay().toMillis());
        registrar.addTriggerTask(scheduler::runOnce, trigger);
    }
}
