package com.github.h3yow.otafleetorchestratorjava.dto;

/** 创建固件版本请求。 */
public record CreateFirmwareRequest(String version, String checksum) {
}
