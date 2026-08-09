package com.mikrotikmanager.domain;

public record TrafficRate(long downloadBps, long uploadBps) {
    public static final TrafficRate UNAVAILABLE = new TrafficRate(0, 0);
}
