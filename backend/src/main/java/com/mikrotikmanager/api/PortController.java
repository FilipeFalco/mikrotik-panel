package com.mikrotikmanager.api;

import com.mikrotikmanager.api.dto.DeviceResponse;
import com.mikrotikmanager.api.dto.PortResponse;
import com.mikrotikmanager.api.dto.SpeedLimitRequest;
import com.mikrotikmanager.api.dto.UpdatePortRequest;
import com.mikrotikmanager.domain.SpeedLimit;
import com.mikrotikmanager.service.PortService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/ports")
public class PortController {
    private final PortService portService;

    public PortController(PortService portService) {
        this.portService = portService;
    }

    @GetMapping
    public List<PortResponse> list() {
        return portService.listPorts().stream().map(ResponseMapper::port).toList();
    }

    @GetMapping("/{interfaceName}")
    public PortResponse get(@PathVariable String interfaceName) {
        return ResponseMapper.port(portService.getPort(interfaceName));
    }

    @PutMapping("/{interfaceName}")
    public PortResponse updateConfiguration(@PathVariable String interfaceName, @Valid @RequestBody UpdatePortRequest request) {
        return ResponseMapper.port(portService.updateConfiguration(interfaceName, request.friendlyName(), request.description(),
                request.network(), request.dhcpServer(), request.enabled(), request.role()));
    }

    @PutMapping("/{interfaceName}/speed")
    public PortResponse setSpeed(@PathVariable String interfaceName, @Valid @RequestBody SpeedLimitRequest request) {
        return ResponseMapper.port(portService.setSpeed(interfaceName,
                new SpeedLimit(request.downloadBps(), request.uploadBps())));
    }

    @GetMapping("/{interfaceName}/devices")
    public List<DeviceResponse> devices(@PathVariable String interfaceName) {
        return portService.getPort(interfaceName).devices().stream().map(ResponseMapper::device).toList();
    }
}
