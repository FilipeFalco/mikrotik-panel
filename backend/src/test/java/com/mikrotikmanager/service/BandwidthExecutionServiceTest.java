package com.mikrotikmanager.service;

import com.mikrotikmanager.domain.BandwidthLimitPolicy;
import com.mikrotikmanager.domain.ManagedSimpleQueue;
import com.mikrotikmanager.domain.ManagedSimpleQueueSemantics;
import com.mikrotikmanager.domain.RouterSimpleQueue;
import com.mikrotikmanager.domain.SpeedLimit;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Phase 5 execution invariants shared by the executor and its fresh preflight. */
class BandwidthExecutionServiceTest {
    @Test void exactOwnedPortQueueIsNotAConflictWithItselfAndMatchesNoOp() {
        ManagedSimpleQueue desired = ManagedSimpleQueue.port("ether2", "10.10.10.0/24", new SpeedLimit(100_000_000, 20_000_000));
        RouterSimpleQueue observed = new RouterSimpleQueue("*1", desired.name(), desired.comment(), desired.target(), desired.maxLimit(), false, false);
        assertThat(ManagedSimpleQueueSemantics.isOwnedPort(observed, "ether2")).isTrue();
        assertThat(ManagedSimpleQueueSemantics.matchesDesired(observed, desired)).isTrue();
    }

    @Test void partialUnlimitedIsInvalidAndUnknownSemanticStateIsFailClosed() {
        assertThat(BandwidthLimitPolicy.isValidPhase5Limit(new SpeedLimit(100_000_000, 0))).isFalse();
        assertThat(BandwidthLimitPolicy.isValidPhase5Limit(SpeedLimit.UNLIMITED)).isTrue();
        RouterSimpleQueue unknown = new RouterSimpleQueue("*1", "mtmgr-port-ether2", "MTMGR:PORT:ether2", "10.10.10.0/24", new SpeedLimit(100_000_000, 20_000_000), false, false, false, "none", null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, Set.of("future-routeros-semantic-field"));
        assertThat(ManagedSimpleQueueSemantics.isSafeManagedQueue(unknown)).isFalse();
    }
}
