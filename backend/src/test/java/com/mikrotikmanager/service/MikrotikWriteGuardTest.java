package com.mikrotikmanager.service;

import com.mikrotikmanager.config.MikrotikProperties;
import com.mikrotikmanager.support.ApiErrorCode;
import com.mikrotikmanager.support.ApiException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MikrotikWriteGuardTest {
    @Test
    void globalFlagIsRequiredEvenWhenDeviceBlockFlagIsEnabled() {
        MikrotikWriteGuard guard = new MikrotikWriteGuard(properties(false, true, "write-user", "write-password"));

        assertThatThrownBy(guard::checkDeviceBlockWriteAllowed)
                .isInstanceOf(ApiException.class)
                .extracting(exception -> ((ApiException) exception).code())
                .isEqualTo(ApiErrorCode.MIKROTIK_WRITES_DISABLED);
    }

    @Test
    void deviceBlockFlagIsRequiredEvenWhenGlobalFlagIsEnabled() {
        MikrotikWriteGuard guard = new MikrotikWriteGuard(properties(true, false, "write-user", "write-password"));

        assertThatThrownBy(guard::checkDeviceBlockWriteAllowed)
                .isInstanceOf(ApiException.class)
                .extracting(exception -> ((ApiException) exception).code())
                .isEqualTo(ApiErrorCode.DEVICE_BLOCK_WRITES_DISABLED);
    }

    @Test
    void readCredentialsNeverSatisfyTheSeparateWriteCredentialRequirement() {
        MikrotikProperties properties = properties(true, true, null, null);
        MikrotikWriteGuard guard = new MikrotikWriteGuard(properties);

        assertThat(properties.writeCredentialsConfigured()).isFalse();
        assertThatThrownBy(guard::checkDeviceBlockWriteAllowed)
                .isInstanceOf(ApiException.class)
                .extracting(exception -> ((ApiException) exception).code())
                .isEqualTo(ApiErrorCode.MIKROTIK_WRITE_CREDENTIALS_MISSING);
    }

    @Test
    void bothFlagsAndSeparateCredentialsPermitTheExecutionServiceToProceed() {
        MikrotikWriteGuard guard = new MikrotikWriteGuard(properties(true, true, "write-user", "write-password"));

        assertThatCode(guard::checkDeviceBlockWriteAllowed).doesNotThrowAnyException();
    }

    @Test
    void mockModeDoesNotRequireWriteCredentials() {
        MikrotikProperties properties = new MikrotikProperties(
                "router", 443, "read-user", "read-password", null, null,
                true, true, false, false, 500, 1_000);

        assertThatCode(() -> new MikrotikWriteGuard(properties).checkDeviceBlockWriteAllowed())
                .doesNotThrowAnyException();
    }

    @Test
    void bandwidthRequiresTheGlobalFlagTheBandwidthFlagAndSeparateCredentials() {
        assertThatThrownBy(() -> new MikrotikWriteGuard(bandwidthProperties(false, true, "write-user", "write-password"))
                .checkBandwidthWriteAllowed())
                .isInstanceOf(ApiException.class)
                .extracting(exception -> ((ApiException) exception).code())
                .isEqualTo(ApiErrorCode.MIKROTIK_WRITES_DISABLED);

        assertThatThrownBy(() -> new MikrotikWriteGuard(bandwidthProperties(true, false, "write-user", "write-password"))
                .checkBandwidthWriteAllowed())
                .isInstanceOf(ApiException.class)
                .extracting(exception -> ((ApiException) exception).code())
                .isEqualTo(ApiErrorCode.BANDWIDTH_WRITES_DISABLED);

        assertThatThrownBy(() -> new MikrotikWriteGuard(bandwidthProperties(true, true, null, null))
                .checkBandwidthWriteAllowed())
                .isInstanceOf(ApiException.class)
                .extracting(exception -> ((ApiException) exception).code())
                .isEqualTo(ApiErrorCode.MIKROTIK_WRITE_CREDENTIALS_MISSING);
    }

    private MikrotikProperties properties(boolean global, boolean deviceBlock,
                                          String writeUsername, String writePassword) {
        return new MikrotikProperties("router", 443, "read-user", "read-password",
                writeUsername, writePassword, true, false, global, deviceBlock, 500, 1_000);
    }

    private MikrotikProperties bandwidthProperties(boolean global, boolean bandwidth,
                                                    String writeUsername, String writePassword) {
        return new MikrotikProperties("router", 443, "read-user", "read-password", writeUsername, writePassword,
                true, false, global, false, bandwidth, 500, 1_000);
    }
}
