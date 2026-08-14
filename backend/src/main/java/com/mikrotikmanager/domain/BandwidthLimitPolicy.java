package com.mikrotikmanager.domain;

/** Phase 5's one authoritative validation rule for Simple Queue limits. */
public final class BandwidthLimitPolicy {
    public static final long MAX_BPS = 10_000_000_000L;

    private BandwidthLimitPolicy() { }

    public static boolean isValidPhase5Limit(SpeedLimit limit) {
        return limit != null
                && limit.downloadBps() >= 0 && limit.uploadBps() >= 0
                && limit.downloadBps() <= MAX_BPS && limit.uploadBps() <= MAX_BPS
                && (limit.isUnlimited() || (limit.downloadBps() > 0 && limit.uploadBps() > 0));
    }
}
