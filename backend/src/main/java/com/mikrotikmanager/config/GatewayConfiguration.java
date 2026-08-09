package com.mikrotikmanager.config;

import com.mikrotikmanager.gateway.MikrotikGateway;
import com.mikrotikmanager.gateway.MockMikrotikGateway;
import com.mikrotikmanager.gateway.UnavailableMikrotikGateway;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({AppProperties.class, MikrotikProperties.class})
class GatewayConfiguration {

    @Bean
    @ConditionalOnProperty(name = "mikrotik.mock-mode", havingValue = "true", matchIfMissing = true)
    MikrotikGateway mockMikrotikGateway(MikrotikProperties properties) {
        return new MockMikrotikGateway(properties.host(), properties.port());
    }

    @Bean
    @ConditionalOnProperty(name = "mikrotik.mock-mode", havingValue = "false")
    MikrotikGateway unavailableMikrotikGateway(MikrotikProperties properties) {
        // The RouterOS REST implementation intentionally starts in Phase 2.
        // This keeps Phase 1 incapable of changing a real router.
        return new UnavailableMikrotikGateway(properties.host(), properties.port());
    }
}
