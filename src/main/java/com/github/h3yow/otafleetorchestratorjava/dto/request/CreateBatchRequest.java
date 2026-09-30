package com.github.h3yow.otafleetorchestratorjava.dto.request;

import java.util.List;

/**
 * 创建升级批次。
 *
 * @param firmwareId             目标（已发布）固件
 * @param deviceIds              计划升级的设备；为空表示该固件适用范围内全部设备
 * @param batchSize              每批下发的设备数量，必须为正
 * @param failureThresholdPercent 失败率百分比阈值（0-100），失败率严格大于该值时自动暂停
 */
public record CreateBatchRequest(String firmwareId,
                                 List<String> deviceIds,
                                 Integer batchSize,
                                 Integer failureThresholdPercent) {
}
