package com.github.h3yow.otafleetorchestratorjava.domain;

import java.time.Instant;

/**
 * 一次状态变化或判定的审计事件。
 *
 * @param time       事件时间
 * @param subject    事件主体（BATCH / DEVICE）
 * @param subjectId  批次或设备 ID
 * @param type       事件类型，如 BATCH_CREATED、REPORT_ACCEPTED、STAGE_ADVANCED
 * @param fromState  变化前状态（可为空）
 * @param toState    变化后状态（可为空）
 * @param detail     判定依据与关键输入，便于解释
 */
public record StateEvent(
        Instant time,
        String subject,
        String subjectId,
        String type,
        String fromState,
        String toState,
        String detail
) {
    public static StateEvent of(String subject, String subjectId, String type,
                                String fromState, String toState, String detail) {
        return new StateEvent(Instant.now(), subject, subjectId, type, fromState, toState, detail);
    }
}
