package com.mikrotikmanager.api;

import com.mikrotikmanager.config.MikrotikProperties;
import com.mikrotikmanager.api.dto.SystemStatusResponse;
import com.mikrotikmanager.gateway.MikrotikGateway;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/system")
public class SystemController {
    private final MikrotikGateway gateway;
    private final MikrotikProperties properties;

    public SystemController(MikrotikGateway gateway, MikrotikProperties properties) {
        this.gateway = gateway;
        this.properties = properties;
    }

    @GetMapping("/status")
    public SystemStatusResponse status() {
        return ResponseMapper.systemStatus(gateway.connectionStatus(), properties);
    }

    @PostMapping("/test-connection")
    public ResponseEntity<SystemStatusResponse> testConnection() {
        return ResponseEntity.ok(ResponseMapper.systemStatus(gateway.connectionStatus(), properties));
    }
}
