package com.mikrotikmanager.domain;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ManagedSimpleQueueSemanticsTest {
    @Test
    void acceptsSingleDefaultSmallTotalQueueButRejectsCustomOrPairedTotalQueue() {
        assertThat(ManagedSimpleQueueSemantics.isSafeManagedQueue(queue("default-small"))).isTrue();
        assertThat(ManagedSimpleQueueSemantics.isSafeManagedQueue(queue("custom-type"))).isFalse();
        assertThat(ManagedSimpleQueueSemantics.isSafeManagedQueue(queue("default-small/default-small"))).isFalse();
    }

    private RouterSimpleQueue queue(String totalQueue) {
        return new RouterSimpleQueue("*Q1", "mtmgr-port-ether2", "MTMGR:PORT:ether2", "10.10.10.0/24",
                new SpeedLimit(100_000_000, 20_000_000), false, false, false, "none",
                null, null, "default-small/default-small", null, null, null, null, null, null, null,
                null, null, null, totalQueue, null, null, null, null, Set.of());
    }
}
