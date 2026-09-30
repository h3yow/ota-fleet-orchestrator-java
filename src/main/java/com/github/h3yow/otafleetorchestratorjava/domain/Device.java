package com.github.h3yow.otafleetorchestratorjava.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 设备注册信息。设备在某次升级中的瞬时状态记录在 {@link DeviceAssignment}。 */
public class Device {

    private final String id;
    private final Instant registeredAt;
    private String currentFirmwareVersion;
    private Instant lastHeartbeatAt;
    private final List<StateEvent> events = new ArrayList<>();

    public Device(String id, String currentFirmwareVersion, Instant registeredAt) {
        this.id = id;
        this.currentFirmwareVersion = currentFirmwareVersion;
        this.registeredAt = registeredAt;
        this.lastHeartbeatAt = registeredAt;
        addEvent("DEVICE_REGISTERED", null, null,
                "device registered currentFirmwareVersion=" + currentFirmwareVersion);
    }

    public String getId() {
        return id;
    }

    public Instant getRegisteredAt() {
        return registeredAt;
    }

    public String getCurrentFirmwareVersion() {
        return currentFirmwareVersion;
    }

    public Instant getLastHeartbeatAt() {
        return lastHeartbeatAt;
    }

    public List<StateEvent> getEvents() {
        return Collections.unmodifiableList(events);
    }

    public void heartbeat(Instant time) {
        this.lastHeartbeatAt = time;
    }

    /** 升级成功后更新设备当前版本。 */
    public void markUpgraded(String firmwareVersion, Instant time) {
        String before = this.currentFirmwareVersion;
        this.currentFirmwareVersion = firmwareVersion;
        addEvent("DEVICE_VERSION_UPDATED", before, firmwareVersion,
                "device firmware version updated at " + time);
    }

    public void addEvent(String type, String fromState, String toState, String detail) {
        events.add(StateEvent.of("DEVICE", id, type, fromState, toState, detail));
    }
}
