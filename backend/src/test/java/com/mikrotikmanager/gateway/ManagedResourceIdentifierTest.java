package com.mikrotikmanager.gateway;

import com.mikrotikmanager.domain.ResourceOwnership;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ManagedResourceIdentifierTest {
    @Test
    void createsStableOwnershipComments() {
        assertThat(ManagedResourceIdentifier.expectedDeviceComment("aa:bb:cc:dd:ee:ff"))
                .isEqualTo("MTMGR:DEVICE:AA-BB-CC-DD-EE-FF");
        assertThat(ManagedResourceIdentifier.expectedPortComment("ether2")).isEqualTo("MTMGR:PORT:ether2");
        assertThat(ManagedResourceIdentifier.expectedPortQueueName("ether2")).isEqualTo("mtmgr-port-ether2");
    }

    @Test
    void requiresExactOwnershipForDevicesAndPortsWithoutNormalizingComments() {
        assertThat(ManagedResourceIdentifier.isOwnedByDevice("MTMGR:DEVICE:AA-BB-CC-DD-EE-FF", "aa:bb:cc:dd:ee:ff"))
                .isTrue();
        assertThat(ManagedResourceIdentifier.isOwnedByDevice("MTMGR:DEVICE:AA-BB-CC-DD-EE-00", "AA:BB:CC:DD:EE:FF"))
                .isFalse();
        assertThat(ManagedResourceIdentifier.isOwnedByDevice("MTMGR:anything", "AA:BB:CC:DD:EE:FF"))
                .isFalse();
        assertThat(ManagedResourceIdentifier.isOwnedByDevice("MTMGR:DEVICE:AA-BB-CC-DD-EE-FF ", "AA:BB:CC:DD:EE:FF"))
                .isFalse();
        assertThat(ManagedResourceIdentifier.isOwnedByDevice(" MTMGR:DEVICE:AA-BB-CC-DD-EE-FF", "AA:BB:CC:DD:EE:FF"))
                .isFalse();
        assertThat(ManagedResourceIdentifier.isOwnedByDevice("MTMGR:DEVICE:AA:BB:CC:DD:EE:FF", "AA:BB:CC:DD:EE:FF"))
                .isFalse();

        assertThat(ManagedResourceIdentifier.isOwnedByPort("MTMGR:PORT:ether2", "ether2")).isTrue();
        assertThat(ManagedResourceIdentifier.isOwnedByPort("MTMGR:PORT:ether3", "ether2")).isFalse();
        assertThat(ManagedResourceIdentifier.isOwnedByPort("MTMGR:anything", "ether2")).isFalse();
        assertThat(ManagedResourceIdentifier.isOwnedByPort("MTMGR:PORT:ether2 ", "ether2")).isFalse();
        assertThat(ManagedResourceIdentifier.isOwnedByPort("MTMGR:PORT:ether2", " ether2 ")).isFalse();
        assertThat(ManagedResourceIdentifier.isOwnedByPort("MTMGR:PORT:ether2:child", "ether2")).isFalse();
    }

    @Test
    void classifiesManualAndUnmarkedResourcesWithoutAdoptingThem() {
        assertThat(ManagedResourceIdentifier.ownershipForPortComment("Criada manualmente", "ether2"))
                .isEqualTo(ResourceOwnership.FOREIGN);
        assertThat(ManagedResourceIdentifier.ownershipForPortComment("MTMGR:PORT:ether3", "ether2"))
                .isEqualTo(ResourceOwnership.FOREIGN);
        assertThat(ManagedResourceIdentifier.ownershipForPortComment(null, "ether2"))
                .isEqualTo(ResourceOwnership.UNKNOWN);
        assertThat(ManagedResourceIdentifier.ownershipForPortComment("MTMGR:PORT:ether2", "ether2"))
                .isEqualTo(ResourceOwnership.MANAGED);

        assertThat(ManagedResourceIdentifier.ownershipForDeviceComment("manual", "AA:BB:CC:DD:EE:FF"))
                .isEqualTo(ResourceOwnership.FOREIGN);
        assertThat(ManagedResourceIdentifier.ownershipForDeviceComment("MTMGR:DEVICE:AA-BB-CC-DD-EE-FF",
                "AA:BB:CC:DD:EE:FF"))
                .isEqualTo(ResourceOwnership.MANAGED);
        assertThat(ManagedResourceIdentifier.ownershipForDeviceComment("MTMGR:DEVICE:AA-BB-CC-DD-EE-FF ",
                "AA:BB:CC:DD:EE:FF"))
                .isEqualTo(ResourceOwnership.FOREIGN);
    }

    @Test
    void rejectsMalformedMacAddresses() {
        assertThatThrownBy(() -> ManagedResourceIdentifier.normalizeMac("not-a-mac"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ManagedResourceIdentifier.expectedPortComment(" "))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
