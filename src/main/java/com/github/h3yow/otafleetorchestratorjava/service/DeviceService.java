package com.github.h3yow.otafleetorchestratorjava.service;

import com.github.h3yow.otafleetorchestratorjava.domain.enums.DeviceStatus;
import com.github.h3yow.otafleetorchestratorjava.exception.DomainException;
import com.github.h3yow.otafleetorchestratorjava.exception.ErrorCode;
import com.github.h3yow.otafleetorchestratorjava.model.Device;
import com.github.h3yow.otafleetorchestratorjava.store.InMemoryStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

@Service
public class DeviceService {

    private static final Logger log = LoggerFactory.getLogger(DeviceService.class);

    private final InMemoryStore store;

    public DeviceService(InMemoryStore store) {
        this.store = store;
    }

    /**
     * 注册设备。
     * 前置条件：deviceId / currentFirmwareVersion 非空；同 deviceId 不得重复注册。
     */
    public Device register(String deviceId, String currentFirmwareVersion) {
        if (deviceId == null || deviceId.isBlank()) {
            throw new DomainException(ErrorCode.INVALID_REQUEST, "deviceId 不能为空");
        }
        if (currentFirmwareVersion == null || currentFirmwareVersion.isBlank()) {
            throw new DomainException(ErrorCode.INVALID_REQUEST, "currentFirmwareVersion 不能为空");
        }
        return store.withDeviceLock(deviceId, () -> {
            if (store.findDevice(deviceId).isPresent()) {
                throw new DomainException(ErrorCode.DEVICE_ALREADY_REGISTERED,
                        "设备已注册: " + deviceId);
            }
            Device device = new Device(deviceId, currentFirmwareVersion, Instant.now());
            store.putDevice(device);
            log.info("[device-register] input=deviceId={}, version={} -> status=ONLINE",
                    deviceId, currentFirmwareVersion);
            return device;
        });
    }

    /**
     * 心跳：设备需已注册；刷新在线状态与最近心跳时间。
     */
    public Device heartbeat(String deviceId) {
        Device device = store.findDevice(deviceId)
                .orElseThrow(() -> new DomainException(ErrorCode.DEVICE_NOT_FOUND,
                        "设备不存在: " + deviceId));
        return store.withDeviceLock(deviceId, () -> {
            Instant now = Instant.now();
            device.setStatus(DeviceStatus.ONLINE);
            device.setLastHeartbeatAt(now);
            log.info("[device-heartbeat] deviceId={}, lastHeartbeat={}", deviceId, now);
            return device;
        });
    }

    public Device get(String deviceId) {
        return store.findDevice(deviceId)
                .orElseThrow(() -> new DomainException(ErrorCode.DEVICE_NOT_FOUND,
                        "设备不存在: " + deviceId));
    }

    public List<Device> list() {
        return store.listDevices();
    }
}
