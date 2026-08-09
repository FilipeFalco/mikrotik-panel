package com.mikrotikmanager.gateway.routeros;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mikrotikmanager.domain.RouterInterface;
import com.mikrotikmanager.domain.TrafficRate;
import com.mikrotikmanager.gateway.routeros.dto.RouterOsInterfaceDto;
import com.mikrotikmanager.gateway.routeros.dto.RouterOsSystemResourceDto;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RouterOsMapperTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void mapsSystemResourceVersionAndIgnoresAdditionalRouterOsProperties() throws Exception {
        RouterOsSystemResourceDto resource = objectMapper.readValue("""
                {
                  "version": "7.18.2 (stable)",
                  "uptime": "2d3h4m",
                  "cpu-load": "17",
                  "architecture-name": "arm",
                  "board-name": "hEX S",
                  "future-routeros-property": "ignored"
                }
                """, RouterOsSystemResourceDto.class);

        assertThat(RouterOsMapper.routerOsVersion(resource)).isEqualTo("7.18.2 (stable)");
        assertThat(resource.cpuLoad()).isEqualTo("17");
        assertThat(resource.architectureName()).isEqualTo("arm");
    }

    @Test
    void mapsRouterOsStringBooleansToExistingInterfaceDomainModel() throws Exception {
        RouterOsInterfaceDto source = objectMapper.readValue("""
                {
                  "name": "ether2",
                  "type": "ether",
                  "running": "true",
                  "disabled": "false",
                  "rx-byte": "123456789",
                  "tx-byte": "987654321"
                }
                """, RouterOsInterfaceDto.class);

        RouterInterface mapped = RouterOsMapper.toRouterInterface(source);

        assertThat(mapped.name()).isEqualTo("ether2");
        assertThat(mapped.type()).isEqualTo("ether");
        assertThat(mapped.running()).isTrue();
        assertThat(mapped.disabled()).isFalse();
        assertThat(mapped.traffic()).isEqualTo(TrafficRate.UNAVAILABLE);
    }

    @Test
    void defaultsMissingOptionalInterfaceBooleansConservatively() {
        RouterInterface mapped = RouterOsMapper.toRouterInterface(new RouterOsInterfaceDto("ether5", "ether", null, ""));

        assertThat(mapped.running()).isFalse();
        assertThat(mapped.disabled()).isFalse();
    }

    @Test
    void rejectsUnexpectedValuesWithoutLeakingTransportContent() {
        assertThatThrownBy(() -> RouterOsMapper.toRouterInterface(
                new RouterOsInterfaceDto("ether2", "ether", "not-a-boolean", "false")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Unexpected RouterOS value: interface.running must be a boolean.");
    }

    @Test
    void parserRejectsMalformedAndOverflowingNumbers() {
        assertThat(RouterOsValueParser.longOrDefault(" 123 ", 0, "rx-byte")).isEqualTo(123L);
        assertThat(RouterOsValueParser.longOrDefault(null, 7, "rx-byte")).isEqualTo(7L);

        assertThatThrownBy(() -> RouterOsValueParser.longOrDefault("9223372036854775808", 0, "rx-byte"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("rx-byte");
        assertThatThrownBy(() -> RouterOsValueParser.longOrDefault("not-a-number", 0, "rx-byte"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("rx-byte");
    }

    @Test
    void requiresAUsableSystemVersion() {
        assertThatThrownBy(() -> RouterOsMapper.routerOsVersion(
                new RouterOsSystemResourceDto(" ", null, null, null, null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("system/resource.version");
    }
}
