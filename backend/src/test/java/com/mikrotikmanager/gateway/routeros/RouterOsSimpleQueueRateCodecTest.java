package com.mikrotikmanager.gateway.routeros;

import com.mikrotikmanager.domain.SpeedLimit;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class RouterOsSimpleQueueRateCodecTest {
    @Test void serializesDomainDownloadUploadAsRouterOsUploadDownload() {
        assertThat(RouterOsSimpleQueueRateCodec.serializeMaxLimit(new SpeedLimit(100_000_000L, 20_000_000L))).isEqualTo("20M/100M");
    }
    @Test void parsesRouterOsUploadDownloadBackToDomainDownloadUpload() {
        assertThat(RouterOsRateParser.parseSimpleQueueMaxLimit("20M/100M")).contains(new SpeedLimit(100_000_000L, 20_000_000L));
    }
}
