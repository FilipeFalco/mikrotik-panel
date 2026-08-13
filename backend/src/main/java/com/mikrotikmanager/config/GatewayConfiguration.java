package com.mikrotikmanager.config;

import com.mikrotikmanager.gateway.MikrotikGateway;
import com.mikrotikmanager.gateway.MockMikrotikGateway;
import com.mikrotikmanager.gateway.MockDeviceBlockMutationGateway;
import com.mikrotikmanager.gateway.DeviceBlockMutationGateway;
import com.mikrotikmanager.gateway.routeros.RouterOsRestGateway;
import com.mikrotikmanager.gateway.routeros.RouterOsWriteClient;
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
    MikrotikGateway routerOsRestGateway(MikrotikProperties properties) {
        return new RouterOsRestGateway(properties);
    }

    @Bean
    @ConditionalOnProperty(name = "mikrotik.mock-mode", havingValue = "true", matchIfMissing = true)
    DeviceBlockMutationGateway mockDeviceBlockMutationGateway(MikrotikGateway gateway) {
        return new MockDeviceBlockMutationGateway(gateway);
    }

    @Bean
    @ConditionalOnProperty(name = "mikrotik.mock-mode", havingValue = "false")
    RouterOsWriteClient routerOsWriteClient(MikrotikProperties properties) {
        return new RouterOsWriteClient(properties);
    }
}
