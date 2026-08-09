package com.mikrotikmanager.gateway;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ManagedResourceIdentifierTest {
    @Test
    void createsStableOwnershipComments() {
        assertThat(ManagedResourceIdentifier.forDevice("aa:bb:cc:dd:ee:ff"))
                .isEqualTo("MTMGR:DEVICE:AA-BB-CC-DD-EE-FF");
        assertThat(ManagedResourceIdentifier.forPort("ether2")).isEqualTo("MTMGR:PORT:ether2");
        assertThat(ManagedResourceIdentifier.isManagedComment("MTMGR:DEVICE:AA-BB-CC-DD-EE-FF")).isTrue();
        assertThat(ManagedResourceIdentifier.isManagedComment("manual rule")).isFalse();
    }

    @Test
    void requiresExactOwnershipForDevicesAndPorts() {
        assertThat(ManagedResourceIdentifier.isOwnedByDevice("MTMGR:DEVICE:AA-BB-CC-DD-EE-FF", "aa:bb:cc:dd:ee:ff"))
                .isTrue();
        assertThat(ManagedResourceIdentifier.isOwnedByDevice("MTMGR:DEVICE:AA-BB-CC-DD-EE-00", "AA:BB:CC:DD:EE:FF"))
                .isFalse();
        assertThat(ManagedResourceIdentifier.isOwnedByDevice("MTMGR:anything", "AA:BB:CC:DD:EE:FF"))
                .isFalse();

        assertThat(ManagedResourceIdentifier.isOwnedByPort("MTMGR:PORT:ether2", "ether2")).isTrue();
        assertThat(ManagedResourceIdentifier.isOwnedByPort("MTMGR:PORT:ether3", "ether2")).isFalse();
        assertThat(ManagedResourceIdentifier.isOwnedByPort("MTMGR:anything", "ether2")).isFalse();
    }

    @Test
    void rejectsMalformedMacAddresses() {
        assertThatThrownBy(() -> ManagedResourceIdentifier.normalizeMac("not-a-mac"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
