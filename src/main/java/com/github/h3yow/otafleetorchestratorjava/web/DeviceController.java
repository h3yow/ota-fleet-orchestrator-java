package com.github.h3yow.otafleetorchestratorjava.web;

import com.github.h3yow.otafleetorchestratorjava.domain.Device;
import com.github.h3yow.otafleetorchestratorjava.dto.DeviceView;
import com.github.h3yow.otafleetorchestratorjava.dto.HeartbeatRequest;
import com.github.h3yow.otafleetorchestratorjava.dto.RegisterDeviceRequest;
import com.github.h3yow.otafleetorchestratorjava.service.DeviceService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 设备注册与心跳接口。 */
@RestController
@RequestMapping("/api/devices")
public class DeviceController {

    private final DeviceService deviceService;

    public DeviceController(DeviceService deviceService) {
        this.deviceService = deviceService;
    }

    @PostMapping
    public ResponseEntity<DeviceView> register(@RequestBody RegisterDeviceRequest request) {
        Device d = deviceService.register(request);
        return ResponseEntity.status(201).body(deviceService.view(d.getId()));
    }

    @GetMapping
    public List<DeviceView> list() {
        return deviceService.list();
    }

    @GetMapping("/{deviceId}")
    public DeviceView get(@PathVariable String deviceId) {
        return deviceService.view(deviceId);
    }

    @PostMapping("/{deviceId}/heartbeat")
    public DeviceView heartbeat(@PathVariable String deviceId,
                                @RequestBody(required = false) HeartbeatRequest request) {
        Device d = deviceService.heartbeat(deviceId);
        return deviceService.view(d.getId());
    }
}
