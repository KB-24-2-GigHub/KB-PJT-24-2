package com.gighub.settlement.config;

import com.gighub.settlement.service.SettlementScheduledPayoutScheduler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.scheduling.config.TriggerTask;
import org.springframework.scheduling.support.PeriodicTrigger;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

@ExtendWith(MockitoExtension.class)
class SettlementSchedulingConfigurerTest {

    @Mock
    private SettlementScheduledPayoutScheduler scheduler;
    @Mock
    private TaskScheduler taskScheduler;

    @Test
    void registersRunOnceWithThePeriodConfiguredExternally() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty(SettlementSchedulerProperties.FIXED_DELAY_MS_KEY, "30000")
                .withProperty(SettlementSchedulerProperties.INITIAL_DELAY_MS_KEY, "5000");
        SettlementSchedulerProperties properties = new SettlementSchedulerProperties(environment);
        SettlementSchedulingConfigurer configurer =
                new SettlementSchedulingConfigurer(scheduler, properties, taskScheduler);
        ScheduledTaskRegistrar registrar = new ScheduledTaskRegistrar();

        configurer.configureTasks(registrar);

        List<TriggerTask> triggerTasks = registrar.getTriggerTaskList();
        assertEquals(taskScheduler, registrar.getScheduler());
        assertEquals(1, triggerTasks.size());
        PeriodicTrigger trigger = assertInstanceOf(
                PeriodicTrigger.class, triggerTasks.get(0).getTrigger());
        assertEquals(30_000L, trigger.getPeriod());
        assertEquals(5_000L, trigger.getInitialDelay());
    }
}
