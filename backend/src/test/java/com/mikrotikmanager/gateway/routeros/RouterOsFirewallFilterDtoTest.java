package com.mikrotikmanager.gateway.routeros;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mikrotikmanager.gateway.routeros.dto.RouterOsFirewallFilterDto;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RouterOsFirewallFilterDtoTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void limitIsAnUnknownRestrictiveMatcher() throws Exception {
        assertThat(dtoWith("limit", "10/1s,10:packet").hasUnknownRestrictiveMatcher()).isTrue();
    }

    @Test
    void timeIsAnUnknownRestrictiveMatcher() throws Exception {
        assertThat(dtoWith("time", "08:00:00-18:00:00,mon,tue,wed,thu,fri").hasUnknownRestrictiveMatcher()).isTrue();
    }

    private RouterOsFirewallFilterDto dtoWith(String matcher, String value) throws Exception {
        return objectMapper.readValue("""
                {".id":"*1","chain":"forward","action":"drop","src-mac-address":"AA:BB:CC:DD:EE:01","comment":"MTMGR:DEVICE:AA-BB-CC-DD-EE-01","disabled":"false","dynamic":"false","%s":"%s"}
                """.formatted(matcher, value), RouterOsFirewallFilterDto.class);
    }
}
