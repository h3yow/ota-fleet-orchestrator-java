package com.github.h3yow.otafleetorchestratorjava.dto;

import java.util.List;

/**
 * 创建升级批次请求。
 *
 * @param name              批次名称
 * @param firmwareId        目标固件 ID
 * @param deviceIds         目标设备 ID 列表（将按设备 ID 排序后确定性地分配到各阶段）
 * @param stagePercents     各阶段设备占比，整数百分比，必须恰好求和为 100，如 [10, 30, 60]
 * @param failureRateLimit  失败率阈值，范围 (0,1)，达到或超过即触发自动暂停+回滚
 */
public record CreateBatchRequest(
        String name,
        String firmwareId,
        List<String> deviceIds,
        List<Integer> stagePercents,
        Double failureRateLimit
) {
}
