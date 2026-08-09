package com.mikrotikmanager.gateway.routeros;

import com.mikrotikmanager.domain.RouterInterface;
import com.mikrotikmanager.domain.TrafficRate;
import com.mikrotikmanager.gateway.routeros.dto.RouterOsInterfaceDto;
import com.mikrotikmanager.gateway.routeros.dto.RouterOsSystemResourceDto;

import java.util.Objects;

/** Maps RouterOS transport DTOs into application domain models. */
public final class RouterOsMapper {
    private RouterOsMapper() {
    }

    public static String routerOsVersion(RouterOsSystemResourceDto resource) {
        Objects.requireNonNull(resource, "resource");
        return RouterOsValueParser.requiredText(resource.version(), "system/resource.version");
    }

    /**
     * Instantaneous traffic is deliberately unavailable in Phase 2. RouterOS
     * interface byte counters are cumulative and must not be presented as a
     * rate without an explicit, tested sampling implementation.
     */
    public static RouterInterface toRouterInterface(RouterOsInterfaceDto source) {
        Objects.requireNonNull(source, "source");
        return new RouterInterface(
                RouterOsValueParser.requiredText(source.name(), "interface.name"),
                RouterOsValueParser.requiredText(source.type(), "interface.type"),
                RouterOsValueParser.booleanOrDefault(source.running(), false, "interface.running"),
                RouterOsValueParser.booleanOrDefault(source.disabled(), false, "interface.disabled"),
                TrafficRate.UNAVAILABLE
        );
    }
}
