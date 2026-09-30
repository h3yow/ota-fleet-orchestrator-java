package com.github.h3yow.otafleetorchestratorjava.web;

import com.github.h3yow.otafleetorchestratorjava.dto.request.RegisterDeviceRequest;
import com.github.h3yow.otafleetorchestratorjava.model.Device;
import com.github.h3yow.otafleetorchestratorjava.service.DeviceService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/devices")
public class DeviceController {

    private final DeviceService deviceService;

    public DeviceController(DeviceService deviceService) {
        this.deviceService = deviceService;
    }

    @PostMapping
    public Device register(@RequestBody RegisterDeviceRequest request) {
        return deviceService.register(request.deviceId(), request.currentFirmwareVersion());
    }

    @PostMapping("/{deviceId}/heartbeat")
    public Device heartbeat(@PathVariable String deviceId) {
        return deviceService.heartbeat(deviceId);
    }

    @GetMapping
    public List<Device> list() {
        return deviceService.list();
    }
}
