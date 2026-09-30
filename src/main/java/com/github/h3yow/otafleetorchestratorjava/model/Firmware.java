package com.github.h3yow.otafleetorchestratorjava.model;

import com.github.h3yow.otafleetorchestratorjava.domain.enums.FirmwareStatus;

import java.time.Instant;

/**
 * 固件版本。创建后为 DRAFT，发布（release）后变为 RELEASED，RELEASED 才能用于建批次。
 */
public class Firmware {

    private final String id;
    private final String version;
    private final Instant createdAt;
    private volatile FirmwareStatus status;
    private volatile Instant releasedAt;

    public Firmware(String id, String version, Instant createdAt) {
        this.id = id;
        this.version = version;
        this.createdAt = createdAt;
        this.status = FirmwareStatus.DRAFT;
    }

    public String getId() {
        return id;
    }

    public String getVersion() {
        return version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public FirmwareStatus getStatus() {
        return status;
    }

    public void setStatus(FirmwareStatus status) {
        this.status = status;
    }

    public Instant getReleasedAt() {
        return releasedAt;
    }

    public void setReleasedAt(Instant releasedAt) {
        this.releasedAt = releasedAt;
    }
}
