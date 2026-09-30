package com.github.h3yow.otafleetorchestratorjava.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 固件版本。 */
public class Firmware {

    private final String id;
    private final String version;
    private final String checksum;
    private final Instant createdAt;
    private final List<StateEvent> events = new ArrayList<>();

    public Firmware(String id, String version, String checksum, Instant createdAt) {
        this.id = id;
        this.version = version;
        this.checksum = checksum;
        this.createdAt = createdAt;
        addEvent("FIRMWARE_CREATED", null, null, "firmware created version=" + version);
    }

    public String getId() {
        return id;
    }

    public String getVersion() {
        return version;
    }

    public String getChecksum() {
        return checksum;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public List<StateEvent> getEvents() {
        return Collections.unmodifiableList(events);
    }

    public void addEvent(String type, String fromState, String toState, String detail) {
        events.add(StateEvent.of("FIRMWARE", id, type, fromState, toState, detail));
    }
}
