package com.mikrotikmanager.gateway.routeros;

import com.mikrotikmanager.domain.SpeedLimit;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RouterOsRateParserTest {

    @Test
    void parsesSupportedRouterOsDecimalRateFormats() {
        assertThat(RouterOsRateParser.parseBitsPerSecond("1000000")).isEqualTo(1_000_000L);
        assertThat(RouterOsRateParser.parseBitsPerSecond("1000k")).isEqualTo(1_000_000L);
        assertThat(RouterOsRateParser.parseBitsPerSecond("1000K")).isEqualTo(1_000_000L);
        assertThat(RouterOsRateParser.parseBitsPerSecond("20M")).isEqualTo(20_000_000L);
        assertThat(RouterOsRateParser.parseBitsPerSecond("1G")).isEqualTo(1_000_000_000L);
    }

    @Test
    void mapsRouterOsUploadDownloadOrderToDomainDownloadUploadOrder() {
        // RouterOS max-limit is upload/download, while SpeedLimit is download/upload.
        assertThat(RouterOsRateParser.parseSimpleQueueMaxLimit("5M/20M"))
                .contains(new SpeedLimit(20_000_000L, 5_000_000L));
        assertThat(RouterOsRateParser.parseSimpleQueueMaxLimit("0/0"))
                .contains(SpeedLimit.UNLIMITED);
    }

    @Test
    void rejectsInvalidAndOverflowingRatesWithoutProducingLimits() {
        assertThatThrownBy(() -> RouterOsRateParser.parseBitsPerSecond("not-a-rate"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Unexpected RouterOS queue rate.");
        assertThatThrownBy(() -> RouterOsRateParser.parseBitsPerSecond("9223372036854775807G"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Unexpected RouterOS queue rate.");

        assertThat(RouterOsRateParser.parseSimpleQueueMaxLimit("20M/not-a-rate")).isEmpty();
        assertThat(RouterOsRateParser.parseSimpleQueueMaxLimit("9223372036854775807G/1G")).isEmpty();
        assertThat(RouterOsRateParser.parseSimpleQueueMaxLimit(" ")).isEmpty();
    }
}
