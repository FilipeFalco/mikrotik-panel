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
    /**
     * Keeps programmatic callers that predate the write kill switch safe by default.
     */
    public MikrotikProperties(String host, int port, String username, String password, boolean verifySsl,
                              boolean mockMode) {
        this(host, port, username, password, verifySsl, mockMode, false);
    }

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
