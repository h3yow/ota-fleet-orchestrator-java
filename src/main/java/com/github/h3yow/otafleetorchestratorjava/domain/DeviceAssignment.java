package com.github.h3yow.otafleetorchestratorjava.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 设备在某个升级批次中的升级记录。
 *
 * <p>{@code state} 只能沿合法链路前进，终态（SUCCESS / FAILED / ROLLBACK_FAILED）不可逆。
 * {@code lastSeq} 单调递增：小序号上报判定为过期请求；相同序号且结果相同判定为重复幂等请求。
 */
public class DeviceAssignment {

    private final String deviceId;
    private final String batchId;
    private final int stageIndex;

    private DeviceState state = DeviceState.IDLE;
    private boolean dispatched;
    private Instant dispatchedAt;
    private long lastSeq = -1L;
    private ReportKind terminalKind;
    private String lastReason;
    private String previousFirmwareVersion;
    private Instant reportedAt;
    private final List<StateEvent> events = new ArrayList<>();

    public DeviceAssignment(String deviceId, String batchId, int stageIndex) {
        this.deviceId = deviceId;
        this.batchId = batchId;
        this.stageIndex = stageIndex;
    }

    public String getDeviceId() {
        return deviceId;
    }

    public String getBatchId() {
        return batchId;
    }

    public int getStageIndex() {
        return stageIndex;
    }

    public DeviceState getState() {
        return state;
    }

    public boolean isDispatched() {
        return dispatched;
    }

    public Instant getDispatchedAt() {
        return dispatchedAt;
    }

    public long getLastSeq() {
        return lastSeq;
    }

    public ReportKind getTerminalKind() {
        return terminalKind;
    }

    public String getLastReason() {
        return lastReason;
    }

    public String getPreviousFirmwareVersion() {
        return previousFirmwareVersion;
    }

    public Instant getReportedAt() {
        return reportedAt;
    }

    public List<StateEvent> getEvents() {
        return Collections.unmodifiableList(events);
    }

    /** 标记为已下发（只能从 IDLE 下发一次），并记录升级前固件版本用于回滚恢复。 */
    public void markDispatched(Instant time, String previousFirmwareVersion) {
        this.previousFirmwareVersion = previousFirmwareVersion;
        DeviceState before = this.state;
        this.dispatched = true;
        this.dispatchedAt = time;
        this.state = DeviceState.PENDING;
        addEvent("DISPATCHED", before.name(), state.name(),
                "upgrade job dispatched stage=" + stageIndex + " at " + time);
    }

    /** 接受一次合法上报并推进状态；返回状态是否真的发生变化（重复幂等请求返回 false）。 */
    public boolean applyReport(ReportKind kind, long seq, String reason, Instant time) {
        DeviceState before = this.state;
        this.lastSeq = seq;
        this.lastReason = reason;
        this.reportedAt = time;
        this.state = switch (kind) {
            case DOWNLOADING -> DeviceState.DOWNLOADING;
            case INSTALLING -> DeviceState.INSTALLING;
            case SUCCESS -> DeviceState.SUCCESS;
            case FAILED -> DeviceState.FAILED;
        };
        boolean changed = before != this.state;
        if (kind == ReportKind.SUCCESS || kind == ReportKind.FAILED) {
            this.terminalKind = kind;
        }
        addEvent("REPORT_ACCEPTED", before.name(), state.name(),
                "kind=" + kind + " seq=" + seq + " changed=" + changed
                        + (reason == null ? "" : " reason=" + reason));
        return changed;
    }

    /** 进入回滚中状态。 */
    public void enterRollingBack(Instant time) {
        DeviceState before = this.state;
        this.state = DeviceState.ROLLING_BACK;
        addEvent("ROLLBACK_STARTED", before.name(), state.name(), "rollback started at " + time);
    }

    /** 回滚成功：设备恢复空闲，可继续参与后续批次。 */
    public void resetToIdle(Instant time) {
        DeviceState before = this.state;
        this.state = DeviceState.IDLE;
        addEvent("ROLLBACK_SUCCEEDED", before.name(), state.name(),
                "device reset to idle at " + time);
    }

    /** 回滚本身失败：保留可解释的终态，设备不可再被普通上报推进。 */
    public void markRollbackFailed(String reason, Instant time) {
        DeviceState before = this.state;
        this.state = DeviceState.ROLLBACK_FAILED;
        this.lastReason = reason;
        addEvent("ROLLBACK_FAILED", before.name(), state.name(),
                "rollback failed reason=" + reason);
    }

    public void addEvent(String type, String fromState, String toState, String detail) {
        events.add(StateEvent.of("DEVICE", deviceId, type, fromState, toState,
                detail + " batch=" + batchId + " stage=" + stageIndex));
    }
}
