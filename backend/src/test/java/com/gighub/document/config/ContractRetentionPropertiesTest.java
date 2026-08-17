package com.gighub.document.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContractRetentionPropertiesTest {

    @Test
    void defaultsToDryRunWhenTheKeyIsMissing() {
        ContractRetentionProperties properties = new ContractRetentionProperties(new MockEnvironment());

        assertFalse(properties.isPurgeEnabled());
    }

    @Test
    void enablesPurgeOnlyWhenExplicitlySetToTrue() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty(ContractRetentionProperties.PURGE_ENABLED_KEY, "true");

        ContractRetentionProperties properties = new ContractRetentionProperties(environment);

        assertTrue(properties.isPurgeEnabled());
    }
}
