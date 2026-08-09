package com.mikrotikmanager.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "mikrotik")
public record MikrotikProperties(
        String host,
        int port,
        String username,
        String password,
        boolean verifySsl,
        boolean mockMode,
        boolean writeEnabled
) {
    @Override
    public String toString() {
        return "MikrotikProperties[host=" + host
                + ", port=" + port
                + ", username=" + username
                + ", password=***"
                + ", verifySsl=" + verifySsl
                + ", mockMode=" + mockMode
                + ", writeEnabled=" + writeEnabled
                + "]";
    }
}
