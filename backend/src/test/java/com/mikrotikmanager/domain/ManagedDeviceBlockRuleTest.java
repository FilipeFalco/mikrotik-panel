package com.mikrotikmanager.domain;

import com.mikrotikmanager.gateway.ManagedResourceIdentifier;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ManagedDeviceBlockRuleTest {
    private static final String MAC = "AA:BB:CC:DD:EE:01";

    @Test
    void ownedRuleWithLimitIsDriftedWithoutLosingOwnership() {
        RouterFirewallFilter rule = restrictiveUnknownRule();

        assertThat(ManagedDeviceBlockRule.isOwned(rule, MAC)).isTrue();
        assertThat(ManagedDeviceBlockRule.isExactDesiredRule(rule, MAC)).isFalse();
    }

    @Test
    void ownedRuleWithTimeIsDriftedWithoutLosingOwnership() {
        RouterFirewallFilter rule = restrictiveUnknownRule();

        assertThat(ManagedDeviceBlockRule.isOwned(rule, MAC)).isTrue();
        assertThat(ManagedDeviceBlockRule.isExactDesiredRule(rule, MAC)).isFalse();
    }

    private RouterFirewallFilter restrictiveUnknownRule() {
        return new RouterFirewallFilter("*1", "drop", "forward", ManagedResourceIdentifier.expectedDeviceComment(MAC),
                false, false, null, null, MAC, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, true);
    }
}
