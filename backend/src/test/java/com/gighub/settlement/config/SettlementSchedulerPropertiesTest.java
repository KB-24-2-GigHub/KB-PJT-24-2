package com.gighub.settlement.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SettlementSchedulerPropertiesTest {

    @Test
    void fallsBackToSafeDefaultsWhenExternalFileHasNoKeys() {
        SettlementSchedulerProperties properties =
                new SettlementSchedulerProperties(new MockEnvironment());

        assertEquals(SettlementSchedulerProperties.DEFAULT_BATCH_SIZE, properties.getBatchSize());
        assertEquals(
                Duration.ofMillis(SettlementSchedulerProperties.DEFAULT_FIXED_DELAY_MS),
                properties.getFixedDelay());
        assertEquals(
                Duration.ofMillis(SettlementSchedulerProperties.DEFAULT_INITIAL_DELAY_MS),
                properties.getInitialDelay());
    }

    @Test
    void readsOverridesFromTheSharedExternalPropertyFile() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty(SettlementSchedulerProperties.BATCH_SIZE_KEY, "25")
                .withProperty(SettlementSchedulerProperties.FIXED_DELAY_MS_KEY, "30000")
                .withProperty(SettlementSchedulerProperties.INITIAL_DELAY_MS_KEY, "5000");

        SettlementSchedulerProperties properties = new SettlementSchedulerProperties(environment);

        assertEquals(25, properties.getBatchSize());
        assertEquals(Duration.ofMillis(30_000L), properties.getFixedDelay());
        assertEquals(Duration.ofMillis(5_000L), properties.getInitialDelay());
    }

    @Test
    void nonPositiveBatchSizeFailsFastAtStartup() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty(SettlementSchedulerProperties.BATCH_SIZE_KEY, "0");

        assertThrows(
                IllegalStateException.class, () -> new SettlementSchedulerProperties(environment));
    }

    @Test
    void nonPositiveFixedDelayFailsFastAtStartup() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty(SettlementSchedulerProperties.FIXED_DELAY_MS_KEY, "-1");

        assertThrows(
                IllegalStateException.class, () -> new SettlementSchedulerProperties(environment));
    }

    @Test
    void nonPositiveInitialDelayFailsFastAtStartup() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty(SettlementSchedulerProperties.INITIAL_DELAY_MS_KEY, "0");

        assertThrows(
                IllegalStateException.class, () -> new SettlementSchedulerProperties(environment));
    }
}
