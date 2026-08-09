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
    void rejectsMalformedMacAddresses() {
        assertThatThrownBy(() -> ManagedResourceIdentifier.normalizeMac("not-a-mac"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
