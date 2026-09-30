package com.github.h3yow.otafleetorchestratorjava.model;

import com.github.h3yow.otafleetorchestratorjava.domain.enums.DeviceUpgradeStatus;

import java.time.Instant;

/**
 * 单设备在单批次中的升级记录（deviceId+batchId 唯一）。
 * <p>
 * 幂等与乱序控制：
 * - processedRequestIds：已处理过的上报 requestId，命中即返回首次结果（HTTP 幂等语义，不重复计数/推进）。
 * - lastSeq：该设备已接受的最大上报序号；seq 小于它的请求判为 STALE_SEQUENCE 拒绝。
 * - status：设备升级状态机，只允许 PENDING->DOWNLOADING->INSTALLING->SUCCESS/FAILED 正向流转，
 *   回滚时由批次统一切到 ROLLED_BACK。
 */
public class DeviceUpgrade {

    private final String deviceId;
    private final String batchId;
    private final String fromVersion;
    private final String toVersion;
    private final Instant dispatchedAt;
    private volatile DeviceUpgradeStatus status;
    private volatile long lastSeq = -1L;
    private volatile Instant updatedAt;

    public DeviceUpgrade(String deviceId, String batchId, String fromVersion, String toVersion, Instant dispatchedAt) {
        this.deviceId = deviceId;
        this.batchId = batchId;
        this.fromVersion = fromVersion;
        this.toVersion = toVersion;
        this.dispatchedAt = dispatchedAt;
        this.status = DeviceUpgradeStatus.PENDING;
        this.updatedAt = dispatchedAt;
    }

    public String getDeviceId() {
        return deviceId;
    }

    public String getBatchId() {
        return batchId;
    }

    public String getFromVersion() {
        return fromVersion;
    }

    public String getToVersion() {
        return toVersion;
    }

    public Instant getDispatchedAt() {
        return dispatchedAt;
    }

    public DeviceUpgradeStatus getStatus() {
        return status;
    }

    public void setStatus(DeviceUpgradeStatus status) {
        this.status = status;
    }

    public long getLastSeq() {
        return lastSeq;
    }

    public void setLastSeq(long lastSeq) {
        this.lastSeq = lastSeq;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
