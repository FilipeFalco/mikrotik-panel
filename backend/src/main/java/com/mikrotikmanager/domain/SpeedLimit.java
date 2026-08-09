package com.mikrotikmanager.domain;

public record SpeedLimit(long downloadBps, long uploadBps) {
    public static final SpeedLimit UNLIMITED = new SpeedLimit(0, 0);

    public boolean isUnlimited() {
        return downloadBps == 0 && uploadBps == 0;
    }
}
