package com.mikrotikmanager.api;

import com.mikrotikmanager.api.dto.DevicePlanRequest;
import com.mikrotikmanager.api.dto.DeviceSpeedPlanRequest;
import com.mikrotikmanager.api.dto.OperationPlanResponse;
import com.mikrotikmanager.api.dto.PortSpeedPlanRequest;
import com.mikrotikmanager.domain.SpeedLimit;
import com.mikrotikmanager.service.OperationPlanningService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Builds local dry-run plans from untrusted intent only.
 *
 * <p>POST is intentionally local HTTP semantics. No endpoint in this
 * controller issues a POST, PUT, PATCH, or DELETE to RouterOS.</p>
 */
@RestController
@RequestMapping("/api/plans")
public class PlanController {
    private final OperationPlanningService operationPlanningService;

    public PlanController(OperationPlanningService operationPlanningService) {
        this.operationPlanningService = operationPlanningService;
    }

    @PostMapping("/block")
    public OperationPlanResponse block(@Valid @RequestBody DevicePlanRequest request) {
        return Phase3ResponseMapper.plan(operationPlanningService.planBlockDevice(request.macAddress()));
    }

    @PostMapping("/unblock")
    public OperationPlanResponse unblock(@Valid @RequestBody DevicePlanRequest request) {
        return Phase3ResponseMapper.plan(operationPlanningService.planUnblockDevice(request.macAddress()));
    }

    @PostMapping("/port-speed")
    public OperationPlanResponse portSpeed(@Valid @RequestBody PortSpeedPlanRequest request) {
        return Phase3ResponseMapper.plan(operationPlanningService.planPortSpeed(request.interfaceName(),
                new SpeedLimit(request.downloadBps(), request.uploadBps())));
    }

    @PostMapping("/device-speed")
    public OperationPlanResponse deviceSpeed(@Valid @RequestBody DeviceSpeedPlanRequest request) {
        return Phase3ResponseMapper.plan(operationPlanningService.planDeviceSpeed(request.macAddress(),
                new SpeedLimit(request.downloadBps(), request.uploadBps())));
    }
}
