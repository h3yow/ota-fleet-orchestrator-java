package com.github.h3yow.otafleetorchestratorjava.exception;

/**
 * 业务拒绝原因。每个非法/过期/不满足前置条件的操作都会被拒绝并返回其中一个可区分的原因。
 */
public enum ErrorCode {

    // ---- 通用 ----
    INVALID_REQUEST("请求参数缺失或不合法"),
    FIRMWARE_NOT_FOUND("固件不存在"),
    DEVICE_NOT_FOUND("设备不存在"),
    BATCH_NOT_FOUND("批次不存在"),

    // ---- 固件 ----
    FIRMWARE_VERSION_DUPLICATED("固件版本号已存在"),
    FIRMWARE_ALREADY_RELEASED("固件已发布，不可重复发布"),
    FIRMWARE_NOT_RELEASED("固件尚未发布，不能用于建批次"),

    // ---- 设备 ----
    DEVICE_ALREADY_REGISTERED("设备已注册，不可重复注册"),

    // ---- 批次生命周期 ----
    BATCH_NO_ELIGIBLE_DEVICE("没有满足条件的设备（设备当前版本须等于批次起始版本）"),
    BATCH_NOT_ACTIVE("批次当前状态不允许推进/暂停（仅 ACTIVE 可推进，ACTIVE 可暂停）"),
    BATCH_NOT_PAUSED("批次未处于 PAUSED，不能恢复"),
    BATCH_NOT_PAUSABLE("只有进行中（ACTIVE）的批次可以暂停"),
    BATCH_HAS_INFLIGHT_DEVICES("当前批次仍有在途设备（PENDING/DOWNLOADING/INSTALLING），不能进入下一批"),
    BATCH_ALREADY_TERMINAL("批次已处于终态（COMPLETED/ROLLED_BACK），不能再操作"),
    BATCH_NOT_ROLLBACKABLE("只有 ACTIVE/PAUSED 的批次可以回滚"),

    // ---- 进度上报 ----
    DEVICE_NOT_IN_BATCH("设备不属于该批次（未被下发或批次不匹配）"),
    REQUEST_ALREADY_PROCESSED("重复请求：requestId 已处理，返回首次结果（幂等）"),
    STALE_SEQUENCE("过期请求：seq 小于该设备已处理的最大序号"),
    ILLEGAL_STATUS_TRANSITION("非法状态迁移：设备当前状态不允许迁移到上报状态"),
    REPORT_REJECTED_TERMINAL("批次已处于终态，拒绝上报");

    private final String reason;

    ErrorCode(String reason) {
        this.reason = reason;
    }

    public String getReason() {
        return reason;
    }
}
