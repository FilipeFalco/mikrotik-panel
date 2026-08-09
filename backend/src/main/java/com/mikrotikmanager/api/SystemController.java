package com.mikrotikmanager.api;

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

    public SystemController(MikrotikGateway gateway) {
        this.gateway = gateway;
    }

    @GetMapping("/status")
    public SystemStatusResponse status() {
        return ResponseMapper.systemStatus(gateway.connectionStatus());
    }

    @PostMapping("/test-connection")
    public ResponseEntity<SystemStatusResponse> testConnection() {
        return ResponseEntity.ok(ResponseMapper.systemStatus(gateway.connectionStatus()));
    }
}
