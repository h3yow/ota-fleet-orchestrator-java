package com.github.h3yow.otafleetorchestratorjava.dto.request;

import com.github.h3yow.otafleetorchestratorjava.domain.enums.DeviceUpgradeStatus;

/**
 * 设备升级进度上报。
 *
 * @param batchId 批次 id
 * @param status  上报的目标状态：DOWNLOADING / INSTALLING / SUCCESS / FAILED
 * @param seq     设备端单调递增序号，小于已处理最大值的请求判为过期
 * @param requestId 幂等键；同一 requestId 的重复请求返回首次结果
 */
public record ProgressReportRequest(String batchId,
                                    DeviceUpgradeStatus status,
                                    Long seq,
                                    String requestId) {
}
