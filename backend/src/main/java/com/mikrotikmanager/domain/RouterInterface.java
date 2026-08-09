package com.mikrotikmanager.domain;

public record RouterInterface(
        String name,
        String type,
        boolean running,
        boolean disabled,
        TrafficRate traffic
) {
}
