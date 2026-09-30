package com.github.h3yow.otafleetorchestratorjava.dto;

import java.time.Instant;

/** 设备视图。 */
public record DeviceView(
        String deviceId,
        String currentFirmwareVersion,
        Instant registeredAt,
        Instant lastHeartbeatAt
) {
}
