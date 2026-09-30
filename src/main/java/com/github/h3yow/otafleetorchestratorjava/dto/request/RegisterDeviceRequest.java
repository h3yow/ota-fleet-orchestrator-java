package com.github.h3yow.otafleetorchestratorjava.dto.request;

/**
 * 注册设备。currentFirmwareVersion 为设备当前固件版本。
 */
public record RegisterDeviceRequest(String deviceId, String currentFirmwareVersion) {
}
