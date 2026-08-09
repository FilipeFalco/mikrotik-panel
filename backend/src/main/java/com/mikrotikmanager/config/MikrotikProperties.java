package com.mikrotikmanager.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

@ConfigurationProperties(prefix = "mikrotik")
public record MikrotikProperties(
        String host,
        int port,
        String username,
        String password,
        boolean verifySsl,
        boolean mockMode,
        boolean writeEnabled,
        int connectTimeoutMs,
        int readTimeoutMs
) {
    public static final int DEFAULT_CONNECT_TIMEOUT_MS = 3_000;
    public static final int DEFAULT_READ_TIMEOUT_MS = 5_000;

    /**
     * Keeps the Phase 1 constructor shape usable in unit tests and in any
     * local callers while providing safe finite defaults for RouterOS REST.
     */
    public MikrotikProperties(
            String host,
            int port,
            String username,
            String password,
            boolean verifySsl,
            boolean mockMode,
            boolean writeEnabled
    ) {
        this(host, port, username, password, verifySsl, mockMode, writeEnabled,
                DEFAULT_CONNECT_TIMEOUT_MS, DEFAULT_READ_TIMEOUT_MS);
    }

    @ConstructorBinding
    public MikrotikProperties {
        if (connectTimeoutMs <= 0) {
            throw new IllegalArgumentException("mikrotik.connect-timeout-ms must be positive");
        }
        if (readTimeoutMs <= 0) {
            throw new IllegalArgumentException("mikrotik.read-timeout-ms must be positive");
        }
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
                + ", connectTimeoutMs=" + connectTimeoutMs
                + ", readTimeoutMs=" + readTimeoutMs
                + "]";
    }
}
