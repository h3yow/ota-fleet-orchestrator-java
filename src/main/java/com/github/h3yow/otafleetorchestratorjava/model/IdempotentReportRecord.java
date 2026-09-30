package com.github.h3yow.otafleetorchestratorjava.model;

import com.github.h3yow.otafleetorchestratorjava.domain.enums.DeviceUpgradeStatus;

import java.time.Instant;

/**
 * 已接受上报的幂等记录（键：deviceId + requestId）。
 * 重复请求命中该记录时直接返回首次处理结果，不再触发状态迁移 / 计数 / 自动暂停判定。
 */
public class IdempotentReportRecord {

    private final String batchId;
    private final DeviceUpgradeStatus resultingStatus;
    private final long seq;
    private final boolean triggeredAutoPause;
    private final Instant processedAt;

    public IdempotentReportRecord(String batchId, DeviceUpgradeStatus resultingStatus,
                                  long seq, boolean triggeredAutoPause, Instant processedAt) {
        this.batchId = batchId;
        this.resultingStatus = resultingStatus;
        this.seq = seq;
        this.triggeredAutoPause = triggeredAutoPause;
        this.processedAt = processedAt;
    }

    public String getBatchId() {
        return batchId;
    }

    public DeviceUpgradeStatus getResultingStatus() {
        return resultingStatus;
    }

    public long getSeq() {
        return seq;
    }

    public boolean isTriggeredAutoPause() {
        return triggeredAutoPause;
    }

    public Instant getProcessedAt() {
        return processedAt;
    }
}
