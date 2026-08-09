package com.mikrotikmanager.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MikrotikPropertiesTest {
    @Test
    void doesNotExposePasswordInToString() {
        String password = "a-real-password-that-must-not-be-logged";
        MikrotikProperties properties = new MikrotikProperties("10.0.0.1", 443, "admin", password, true,
                false, false);

        assertThat(properties.toString())
                .contains("password=***")
                .doesNotContain(password);
    }

    @Test
    void legacyConstructorUsesFiniteRouterOsTimeoutDefaults() {
        MikrotikProperties properties = new MikrotikProperties("10.0.0.1", 443, "admin", "secret", true,
                false, false);

        assertThat(properties.connectTimeoutMs()).isEqualTo(MikrotikProperties.DEFAULT_CONNECT_TIMEOUT_MS);
        assertThat(properties.readTimeoutMs()).isEqualTo(MikrotikProperties.DEFAULT_READ_TIMEOUT_MS);
    }

    @Test
    void rejectsNonPositiveRouterOsTimeouts() {
        assertThatThrownBy(() -> new MikrotikProperties("10.0.0.1", 443, "admin", "secret", true,
                false, false, 0, 5_000))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("connect-timeout-ms");
        assertThatThrownBy(() -> new MikrotikProperties("10.0.0.1", 443, "admin", "secret", true,
                false, false, 3_000, -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("read-timeout-ms");
    }
}
