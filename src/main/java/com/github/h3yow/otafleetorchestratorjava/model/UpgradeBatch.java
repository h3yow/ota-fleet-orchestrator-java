package com.github.h3yow.otafleetorchestratorjava.model;

import com.github.h3yow.otafleetorchestratorjava.domain.enums.BatchStatus;
import com.github.h3yow.otafleetorchestratorjava.domain.enums.PauseReason;

import java.time.Instant;
import java.util.List;

/**
 * 升级批次聚合。
 * <p>
 * 生命周期：PENDING -> ACTIVE -> PAUSED -> ACTIVE ... -> COMPLETED；任意 ACTIVE/PAUSED 可回滚为 ROLLED_BACK。
 * <p>
 * 分批推进：eligibleDeviceIds 是建批次时固化的设备快照（按 id 排序），每次 advance
 * 从游标 dispatchedCount 处切 batchSize 个设备下发；只有当前一批全部终态（无在途）才能推进下一批。
 * <p>
 * 计数在批次锁内维护：successCount/failedCount 只随"被接受的新上报"变化，
 * 重复/过期/非法请求不会改动计数；失败率 = failed / (success + failed + 在途)。
 */
public class UpgradeBatch {

    private final String id;
    private final String firmwareId;
    private final String fromVersion;
    private final String targetVersion;
    private final int batchSize;
    /** 失败率百分比阈值（0-100），失败率严格大于该值时自动暂停 */
    private final int failureThresholdPercent;
    private final List<String> eligibleDeviceIds;
    private final Instant createdAt;

    private volatile BatchStatus status = BatchStatus.PENDING;
    /** 当前已下发到第几批，-1 表示尚未开始 */
    private volatile int currentStage = -1;
    /** 已下发设备数（游标） */
    private volatile int dispatchedCount = 0;
    private volatile int successCount = 0;
    private volatile int failedCount = 0;
    private volatile PauseReason pauseReason;
    private volatile Instant pausedAt;
    private volatile Instant completedAt;
    private volatile Instant rolledBackAt;

    public UpgradeBatch(String id, String firmwareId, String fromVersion, String targetVersion,
                        int batchSize, int failureThresholdPercent,
                        List<String> eligibleDeviceIds, Instant createdAt) {
        this.id = id;
        this.firmwareId = firmwareId;
        this.fromVersion = fromVersion;
        this.targetVersion = targetVersion;
        this.batchSize = batchSize;
        this.failureThresholdPercent = failureThresholdPercent;
        this.eligibleDeviceIds = List.copyOf(eligibleDeviceIds);
        this.createdAt = createdAt;
    }

    public String getId() {
        return id;
    }

    public String getFirmwareId() {
        return firmwareId;
    }

    public String getFromVersion() {
        return fromVersion;
    }

    public String getTargetVersion() {
        return targetVersion;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public int getFailureThresholdPercent() {
        return failureThresholdPercent;
    }

    public List<String> getEligibleDeviceIds() {
        return eligibleDeviceIds;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public BatchStatus getStatus() {
        return status;
    }

    public void setStatus(BatchStatus status) {
        this.status = status;
    }

    public int getCurrentStage() {
        return currentStage;
    }

    public void setCurrentStage(int currentStage) {
        this.currentStage = currentStage;
    }

    public int getDispatchedCount() {
        return dispatchedCount;
    }

    public void setDispatchedCount(int dispatchedCount) {
        this.dispatchedCount = dispatchedCount;
    }

    public int getSuccessCount() {
        return successCount;
    }

    public void setSuccessCount(int successCount) {
        this.successCount = successCount;
    }

    public int getFailedCount() {
        return failedCount;
    }

    public void setFailedCount(int failedCount) {
        this.failedCount = failedCount;
    }

    public PauseReason getPauseReason() {
        return pauseReason;
    }

    public void setPauseReason(PauseReason pauseReason) {
        this.pauseReason = pauseReason;
    }

    public Instant getPausedAt() {
        return pausedAt;
    }

    public void setPausedAt(Instant pausedAt) {
        this.pausedAt = pausedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(Instant completedAt) {
        this.completedAt = completedAt;
    }

    public Instant getRolledBackAt() {
        return rolledBackAt;
    }

    public void setRolledBackAt(Instant rolledBackAt) {
        this.rolledBackAt = rolledBackAt;
    }
}
