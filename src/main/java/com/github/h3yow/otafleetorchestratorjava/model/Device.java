package com.github.h3yow.otafleetorchestratorjava.model;

import com.github.h3yow.otafleetorchestratorjava.domain.enums.DeviceStatus;

import java.time.Instant;

/**
 * 设备。注册即 ONLINE；心跳刷新 lastHeartbeatAt。
 * currentFirmwareVersion 为设备当前真实运行的版本，升级成功 / 回滚时更新。
 * targetBatchId 标记设备当前被下发但尚未终态的批次（同一时刻最多一个），成功/失败/回滚后清空。
 */
public class Device {

    private final String id;
    private final Instant registeredAt;
    private volatile String currentFirmwareVersion;
    private volatile DeviceStatus status;
    private volatile Instant lastHeartbeatAt;
    private volatile String targetBatchId;

    public Device(String id, String currentFirmwareVersion, Instant registeredAt) {
        this.id = id;
        this.currentFirmwareVersion = currentFirmwareVersion;
        this.registeredAt = registeredAt;
        this.status = DeviceStatus.ONLINE;
        this.lastHeartbeatAt = registeredAt;
    }

    public String getId() {
        return id;
    }

    public Instant getRegisteredAt() {
        return registeredAt;
    }

    public String getCurrentFirmwareVersion() {
        return currentFirmwareVersion;
    }

    public void setCurrentFirmwareVersion(String currentFirmwareVersion) {
        this.currentFirmwareVersion = currentFirmwareVersion;
    }

    public DeviceStatus getStatus() {
        return status;
    }

    public void setStatus(DeviceStatus status) {
        this.status = status;
    }

    public Instant getLastHeartbeatAt() {
        return lastHeartbeatAt;
    }

    public void setLastHeartbeatAt(Instant lastHeartbeatAt) {
        this.lastHeartbeatAt = lastHeartbeatAt;
    }

    public String getTargetBatchId() {
        return targetBatchId;
    }

    public void setTargetBatchId(String targetBatchId) {
        this.targetBatchId = targetBatchId;
    }
}
