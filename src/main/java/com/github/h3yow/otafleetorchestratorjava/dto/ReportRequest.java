package com.github.h3yow.otafleetorchestratorjava.dto;

/**
 * 设备升级进度上报。
 *
 * @param seq    设备侧单调递增的请求序号，用于过期/重复判定
 * @param status  DOWNLOADING / INSTALLING / SUCCESS / FAILED
 * @param reason 失败原因或补充说明（可空）
 */
public record ReportRequest(Long seq, String status, String reason) {
}
