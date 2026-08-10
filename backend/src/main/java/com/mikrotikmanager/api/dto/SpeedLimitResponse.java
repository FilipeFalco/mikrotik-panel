package com.mikrotikmanager.api.dto;

/** Safe API representation of a bandwidth value; zero means unlimited. */
public record SpeedLimitResponse(long downloadBps, long uploadBps) {
}
