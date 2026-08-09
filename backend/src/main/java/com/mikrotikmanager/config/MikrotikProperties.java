package com.mikrotikmanager.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "mikrotik")
public record MikrotikProperties(
        String host,
        int port,
        String username,
        String password,
        boolean verifySsl,
        boolean mockMode
) {
}
