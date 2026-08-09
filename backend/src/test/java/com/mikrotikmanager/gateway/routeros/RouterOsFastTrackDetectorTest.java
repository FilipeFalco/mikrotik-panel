package com.mikrotikmanager.gateway.routeros;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mikrotikmanager.gateway.routeros.dto.RouterOsFirewallFilterDto;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RouterOsFastTrackDetectorTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void detectsOnlyEnabledFastTrackConnectionRules() {
        RouterOsFirewallFilterDto active = new RouterOsFirewallFilterDto(
                "*1", "fasttrack-connection", "false", "false", "forward", "defconf: fasttrack");
        RouterOsFirewallFilterDto disabled = new RouterOsFirewallFilterDto(
                "*2", "fasttrack-connection", "true", "false", "forward", "disabled fasttrack");
        RouterOsFirewallFilterDto nonFastTrack = new RouterOsFirewallFilterDto(
                "*3", "accept", "false", "false", "forward", "accept established");

        List<RouterOsFastTrackRule> detected = RouterOsFastTrackDetector.findActiveRules(
                List.of(active, disabled, nonFastTrack));

        assertThat(RouterOsFastTrackDetector.isDetected(List.of(active, disabled, nonFastTrack))).isTrue();
        assertThat(detected).containsExactly(new RouterOsFastTrackRule("forward", "defconf: fasttrack"));
    }

    @Test
    void doesNotReportOnlyDisabledFastTrackRules() {
        RouterOsFirewallFilterDto disabled = new RouterOsFirewallFilterDto(
                "*2", "fasttrack-connection", "true", "false", "forward", "disabled fasttrack");

        assertThat(RouterOsFastTrackDetector.isDetected(List.of(disabled))).isFalse();
        assertThat(RouterOsFastTrackDetector.findActiveRules(List.of(disabled))).isEmpty();
    }

    @Test
    void keepsFirewallFilterRouterOsStringTransportAndIgnoresUnknownProperties() throws Exception {
        RouterOsFirewallFilterDto filter = objectMapper.readValue("""
                {
                  ".id": "*1",
                  "action": "fasttrack-connection",
                  "disabled": "false",
                  "dynamic": "false",
                  "chain": "forward",
                  "comment": "defconf: fasttrack",
                  "future-routeros-property": "ignored"
                }
                """, RouterOsFirewallFilterDto.class);

        assertThat(filter.id()).isEqualTo("*1");
        assertThat(filter.action()).isEqualTo("fasttrack-connection");
        assertThat(filter.disabled()).isEqualTo("false");
        assertThat(filter.chain()).isEqualTo("forward");
        assertThat(filter.comment()).isEqualTo("defconf: fasttrack");
    }
}
