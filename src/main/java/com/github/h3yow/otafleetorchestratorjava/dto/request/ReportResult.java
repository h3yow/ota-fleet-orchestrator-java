package com.github.h3yow.otafleetorchestratorjava.dto.request;

import com.github.h3yow.otafleetorchestratorjava.domain.enums.BatchStatus;
import com.github.h3yow.otafleetorchestratorjava.domain.enums.DeviceUpgradeStatus;

/**
 * 一次进度上报的处理结果。duplicate=true 表示命中幂等（返回首次结果，未发生状态迁移/计数变化）。
 */
public record ReportResult(String deviceId,
                           String batchId,
                           String requestId,
                           DeviceUpgradeStatus deviceStatus,
                           BatchStatus batchStatus,
                           boolean duplicate,
                           boolean triggeredAutoPause,
                           long successCount,
                           long failedCount,
                           String decision) {
}
