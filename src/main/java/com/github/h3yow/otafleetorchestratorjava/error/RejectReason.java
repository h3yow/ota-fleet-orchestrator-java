package com.github.h3yow.otafleetorchestratorjava.error;

/**
 * 可区分的业务拒绝原因（错误码）。
 * 每种非法操作 / 过期请求 / 不满足前置条件的情形都有独立编码，便于调用方区分处理。
 */
public enum RejectReason {

    // ---- 入参校验 ----
    VALIDATION_FAILED(400),
    EMPTY_DEVICE_LIST(400),
    INVALID_STAGE_PLAN(400),

    // ---- 固件 ----
    FIRMWARE_NOT_FOUND(404),
    FIRMWARE_ALREADY_EXISTS(409),
    FIRMWARE_IN_USE(409),

    // ---- 设备 ----
    DEVICE_NOT_FOUND(404),
    DEVICE_ALREADY_REGISTERED(409),
    DEVICE_NOT_ASSIGNED(409),

    // ---- 批次前置条件 ----
    BATCH_NOT_FOUND(404),
    BATCH_NOT_DRAFT(409),
    BATCH_NOT_ROLLING(409),
    BATCH_NOT_PAUSED(409),
    BATCH_ALREADY_PAUSED(409),
    BATCH_FINISHED(409),
    ROLLBACK_IN_PROGRESS(409),
    STAGE_NOT_READY(409),

    // ---- 上报幂等 / 乱序 ----
    STAGE_MISMATCH(409),
    STAGE_NOT_ACTIVE(409),
    BATCH_PAUSED(409),
    STALE_REQUEST(409),
    DUPLICATE_TERMINAL_REPORT(409),
    ILLEGAL_STATE_TRANSITION(409);

    private final int httpStatus;

    RejectReason(int httpStatus) {
        this.httpStatus = httpStatus;
    }

    public int httpStatus() {
        return httpStatus;
    }

    public String code() {
        return name();
    }
}
