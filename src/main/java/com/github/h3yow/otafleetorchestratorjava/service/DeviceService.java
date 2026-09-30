package com.github.h3yow.otafleetorchestratorjava.service;

import com.github.h3yow.otafleetorchestratorjava.domain.Device;
import com.github.h3yow.otafleetorchestratorjava.dto.DeviceView;
import com.github.h3yow.otafleetorchestratorjava.dto.RegisterDeviceRequest;
import com.github.h3yow.otafleetorchestratorjava.error.BusinessException;
import com.github.h3yow.otafleetorchestratorjava.error.RejectReason;
import com.github.h3yow.otafleetorchestratorjava.store.InMemoryStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/** 设备注册与心跳。心跳天然幂等：重复心跳只刷新最后心跳时间。 */
@Service
public class DeviceService {

    private static final Logger log = LoggerFactory.getLogger(DeviceService.class);

    private final InMemoryStore store;

    public DeviceService(InMemoryStore store) {
        this.store = store;
    }

    public Device register(RegisterDeviceRequest req) {
        if (req == null) {
            throw new BusinessException(RejectReason.VALIDATION_FAILED, "请求体不能为空");
        }
        return store.withLock(() -> {
            String id = (req.deviceId() == null || req.deviceId().isBlank())
                    ? store.nextDeviceId() : req.deviceId().trim();
            if (store.devices().containsKey(id)) {
                throw new BusinessException(RejectReason.DEVICE_ALREADY_REGISTERED,
                        "设备 " + id + " 已注册，重复注册被拒绝");
            }
            Device device = new Device(id, req.currentFirmwareVersion(), Instant.now());
            store.devices().put(id, device);
            log.info("REGISTER_DEVICE input=[id={}, fw={}] -> registered", id,
                    req.currentFirmwareVersion());
            return device;
        });
    }

    public Device heartbeat(String deviceId) {
        return store.withLock(() -> {
            Device device = requireDevice(deviceId);
            Instant now = Instant.now();
            device.heartbeat(now);
            log.info("HEARTBEAT device={} at={} (idempotent)", deviceId, now);
            return device;
        });
    }

    public Device requireDevice(String deviceId) {
        Device device = store.devices().get(deviceId);
        if (device == null) {
            throw new BusinessException(RejectReason.DEVICE_NOT_FOUND,
                    "设备 " + deviceId + " 未注册");
        }
        return device;
    }

    public DeviceView view(String deviceId) {
        Device d = requireDevice(deviceId);
        return new DeviceView(d.getId(), d.getCurrentFirmwareVersion(),
                d.getRegisteredAt(), d.getLastHeartbeatAt());
    }

    public List<DeviceView> list() {
        return store.devices().values().stream()
                .map(d -> new DeviceView(d.getId(), d.getCurrentFirmwareVersion(),
                        d.getRegisteredAt(), d.getLastHeartbeatAt()))
                .toList();
    }
}
