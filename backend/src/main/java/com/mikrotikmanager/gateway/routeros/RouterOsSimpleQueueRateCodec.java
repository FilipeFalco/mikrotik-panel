package com.mikrotikmanager.gateway.routeros;

import com.mikrotikmanager.domain.SpeedLimit;

/** The sole write-side conversion between domain download/upload and RouterOS upload/download. */
public final class RouterOsSimpleQueueRateCodec {
    private RouterOsSimpleQueueRateCodec() { }

    public static String serializeMaxLimit(SpeedLimit limit) {
        if (limit == null || limit.downloadBps() <= 0 || limit.uploadBps() <= 0) {
            throw new IllegalArgumentException("Simple Queue MIR requires two finite directions in Phase 5.");
        }
        return rate(limit.uploadBps()) + "/" + rate(limit.downloadBps());
    }

    private static String rate(long bps) {
        if (bps % 1_000_000L == 0) return (bps / 1_000_000L) + "M";
        if (bps % 1_000L == 0) return (bps / 1_000L) + "k";
        return Long.toString(bps);
    }
}
