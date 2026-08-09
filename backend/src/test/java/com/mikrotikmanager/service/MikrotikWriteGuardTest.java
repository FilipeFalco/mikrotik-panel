package com.mikrotikmanager.service;

import com.mikrotikmanager.config.MikrotikProperties;
import com.mikrotikmanager.support.ApiException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MikrotikWriteGuardTest {
    @Test
    void permitsMockMutationsWhenWriteSwitchIsDisabled() {
        MikrotikWriteGuard guard = guard(true, false);

        assertThatCode(guard::checkWriteAllowed).doesNotThrowAnyException();
    }

    @Test
    void rejectsRealMutationsWhenWriteSwitchIsDisabled() {
        MikrotikWriteGuard guard = guard(false, false);

        assertThatThrownBy(guard::checkWriteAllowed)
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("escrita");
    }

    @Test
    void permitsRealMutationsOnlyWhenWriteSwitchIsEnabled() {
        MikrotikWriteGuard guard = guard(false, true);

        assertThatCode(guard::checkWriteAllowed).doesNotThrowAnyException();
    }

    private MikrotikWriteGuard guard(boolean mockMode, boolean writeEnabled) {
        return new MikrotikWriteGuard(new MikrotikProperties("10.0.0.1", 443, "admin", "secret", true,
                mockMode, writeEnabled));
    }
}
