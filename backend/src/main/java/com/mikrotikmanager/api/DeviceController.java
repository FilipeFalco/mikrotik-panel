package com.mikrotikmanager.api;

import com.mikrotikmanager.api.dto.DeviceResponse;
import com.mikrotikmanager.api.dto.SpeedLimitRequest;
import com.mikrotikmanager.api.dto.UpdateDeviceRequest;
import com.mikrotikmanager.domain.SpeedLimit;
import com.mikrotikmanager.service.DeviceService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/devices")
public class DeviceController {
    private final DeviceService deviceService;

    public DeviceController(DeviceService deviceService) {
        this.deviceService = deviceService;
    }

    @GetMapping
    public List<DeviceResponse> list() {
        return deviceService.listDevices().stream().map(ResponseMapper::device).toList();
    }

    @GetMapping("/{macAddress}")
    public DeviceResponse get(@PathVariable String macAddress) {
        return ResponseMapper.device(deviceService.getDevice(macAddress));
    }

    @PutMapping("/{macAddress}")
    public DeviceResponse updateMetadata(@PathVariable String macAddress, @Valid @RequestBody UpdateDeviceRequest request) {
        return ResponseMapper.device(deviceService.updateMetadata(macAddress, request.friendlyName(), request.notes()));
    }

    @PutMapping("/{macAddress}/speed")
    public DeviceResponse setSpeed(@PathVariable String macAddress, @Valid @RequestBody SpeedLimitRequest request) {
        return ResponseMapper.device(deviceService.setSpeed(macAddress,
                new SpeedLimit(request.downloadBps(), request.uploadBps())));
    }

    @PostMapping("/{macAddress}/block")
    public DeviceResponse block(@PathVariable String macAddress) {
        return ResponseMapper.device(deviceService.block(macAddress));
    }

    @DeleteMapping("/{macAddress}/block")
    public DeviceResponse unblock(@PathVariable String macAddress) {
        return ResponseMapper.device(deviceService.unblock(macAddress));
    }
}
