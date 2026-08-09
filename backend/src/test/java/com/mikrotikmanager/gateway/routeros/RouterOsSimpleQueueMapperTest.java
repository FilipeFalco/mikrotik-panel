package com.mikrotikmanager.gateway.routeros;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mikrotikmanager.domain.SpeedLimit;
import com.mikrotikmanager.gateway.routeros.dto.RouterOsSimpleQueueDto;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RouterOsSimpleQueueMapperTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void mapsOnlyExactApplicationOwnedQueueAndPreservesRateDirectionWhenTargetIsCidr() {
        RouterOsSimpleQueueDto owned = new RouterOsSimpleQueueDto(
                "*A", "mtmgr-port-ether2", "MTMGR:PORT:ether2", "5M/20M", "false", "false", "10.10.10.0/24");
        RouterOsSimpleQueueDto manual = new RouterOsSimpleQueueDto(
                "*B", "manual-speed", "Minha queue manual", "1M/50M", "false", "false", "10.10.10.0/24");
        RouterOsSimpleQueueDto missingPortMarker = new RouterOsSimpleQueueDto(
                "*C", "unsafe-copy", "MTMGR:PORT:", "10M/10M", "false", "false", "10.10.10.0/24");

        Map<String, SpeedLimit> portSpeeds = RouterOsSimpleQueueMapper.toOwnedPortSpeeds(
                List.of(owned, manual, missingPortMarker));

        assertThat(portSpeeds).containsExactly(Map.entry("ether2", new SpeedLimit(20_000_000L, 5_000_000L)));
    }

    @Test
    void skipsDisabledAndMalformedOwnedQueuesWithoutBreakingValidResults() {
        RouterOsSimpleQueueDto valid = new RouterOsSimpleQueueDto(
                "*A", "mtmgr-port-ether2", "MTMGR:PORT:ether2", "10M/100M", "false", "false", "10.10.10.0/24");
        RouterOsSimpleQueueDto malformed = new RouterOsSimpleQueueDto(
                "*B", "mtmgr-port-ether3", "MTMGR:PORT:ether3", "too-fast/20M", "false", "false", "10.10.20.0/24");
        RouterOsSimpleQueueDto disabled = new RouterOsSimpleQueueDto(
                "*C", "mtmgr-port-ether4", "MTMGR:PORT:ether4", "10M/20M", "true", "false", "10.10.30.0/24");

        Map<String, SpeedLimit> portSpeeds = RouterOsSimpleQueueMapper.toOwnedPortSpeeds(
                List.of(valid, malformed, disabled));

        assertThat(portSpeeds).containsExactly(Map.entry("ether2", new SpeedLimit(100_000_000L, 10_000_000L)));
    }

    @Test
    void keepsRouterOsStringTransportAndIgnoresUnknownProperties() throws Exception {
        RouterOsSimpleQueueDto queue = objectMapper.readValue("""
                {
                  ".id": "*A",
                  "name": "mtmgr-port-ether2",
                  "comment": "MTMGR:PORT:ether2",
                  "max-limit": "5M/20M",
                  "disabled": "false",
                  "dynamic": "false",
                  "target": "10.10.10.0/24",
                  "future-routeros-property": "ignored"
                }
                """, RouterOsSimpleQueueDto.class);

        assertThat(queue.id()).isEqualTo("*A");
        assertThat(queue.maxLimit()).isEqualTo("5M/20M");
        assertThat(queue.disabled()).isEqualTo("false");
        assertThat(queue.dynamic()).isEqualTo("false");
        assertThat(queue.target()).isEqualTo("10.10.10.0/24");
    }
}
