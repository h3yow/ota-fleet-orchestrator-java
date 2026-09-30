package com.github.h3yow.otafleetorchestratorjava.dto;

/** 设备注册请求；deviceId 可空，缺省由服务端生成。 */
public record RegisterDeviceRequest(String deviceId, String currentFirmwareVersion) {
}
