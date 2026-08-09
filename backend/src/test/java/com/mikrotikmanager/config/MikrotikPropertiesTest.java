package com.mikrotikmanager.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

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
}
