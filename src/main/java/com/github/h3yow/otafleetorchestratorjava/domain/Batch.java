package com.github.h3yow.otafleetorchestratorjava.domain;

import com.github.h3yow.otafleetorchestratorjava.error.BusinessException;
import com.github.h3yow.otafleetorchestratorjava.error.RejectReason;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 升级批次：持有分批计划、设备分配与批次状态机。
 *
 * <p>本类所有读写均必须在存储层全局锁内进行，因此不做内部同步。
 */
public class Batch {

    /** 单个阶段的计划：索引、目标设备占比（%）、实际设备数。 */
    public record Stage(int index, int percent, int size) {
    }

    private final String id;
    private final String firmwareId;
    private final String name;
    private final double failureRateLimit;
    private final Instant createdAt;

    private final List<Stage> stages = new ArrayList<>();
    private final Map<String, DeviceAssignment> assignments = new LinkedHashMap<>();

    private BatchStatus status = BatchStatus.DRAFT;
    private int activeStage = -1;
    private String pauseReason;
    private String rollbackReason;

    /** 回滚触发时刻的计数快照：回滚后设备状态重置为 IDLE，快照保留判定依据。 */
    private int rollbackSnapshotReported = -1;
    private int rollbackSnapshotSuccess = -1;
    private int rollbackSnapshotFailure = -1;
    private double rollbackSnapshotFailureRate = -1.0;

    private Instant startedAt;
    private Instant pausedAt;
    private Instant completedAt;
    private Instant rolledBackAt;

    private final List<StateEvent> events = new ArrayList<>();

    public Batch(String id, String firmwareId, String name, List<Integer> stagePercents,
                 double failureRateLimit, List<String> orderedDeviceIds, Instant createdAt) {
        this.id = id;
        this.firmwareId = firmwareId;
        this.name = name;
        this.failureRateLimit = failureRateLimit;
        this.createdAt = createdAt;
        buildStages(stagePercents, orderedDeviceIds);
        int cursor = 0;
        for (Stage s : stages) {
            for (int i = 0; i < s.size(); i++) {
                String deviceId = orderedDeviceIds.get(cursor);
                assignments.put(deviceId, new DeviceAssignment(deviceId, id, s.index()));
                cursor++;
            }
        }
        addEvent("BATCH_CREATED", null, status.name(),
                "firmware=" + firmwareId + " devices=" + orderedDeviceIds.size()
                        + " stages=" + stagePercents + " failureRateLimit=" + failureRateLimit);
    }

    /** 根据百分比计算每阶段实际设备数，取整余数全部并入最后阶段，保证各阶段设备数之和等于总数。 */
    private void buildStages(List<Integer> percents, List<String> deviceIds) {
        int total = deviceIds.size();
        int allocated = 0;
        for (int i = 0; i < percents.size(); i++) {
            int size;
            if (i == percents.size() - 1) {
                size = total - allocated;
            } else {
                size = (int) Math.floor((double) percents.get(i) * total / 100.0);
                allocated += size;
            }
            stages.add(new Stage(i, percents.get(i), size));
        }
    }

    // ---------- 基础访问器 ----------

    public String getId() {
        return id;
    }

    public String getFirmwareId() {
        return firmwareId;
    }

    public String getName() {
        return name;
    }

    public double getFailureRateLimit() {
        return failureRateLimit;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public List<Stage> getStages() {
        return Collections.unmodifiableList(stages);
    }

    public Map<String, DeviceAssignment> getAssignments() {
        return Collections.unmodifiableMap(assignments);
    }

    public BatchStatus getStatus() {
        return status;
    }

    public int getActiveStage() {
        return activeStage;
    }

    public String getPauseReason() {
        return pauseReason;
    }

    public String getRollbackReason() {
        return rollbackReason;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getPausedAt() {
        return pausedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public Instant getRolledBackAt() {
        return rolledBackAt;
    }

    public int getRollbackSnapshotReported() {
        return rollbackSnapshotReported;
    }

    public int getRollbackSnapshotSuccess() {
        return rollbackSnapshotSuccess;
    }

    public int getRollbackSnapshotFailure() {
        return rollbackSnapshotFailure;
    }

    public double getRollbackSnapshotFailureRate() {
        return rollbackSnapshotFailureRate;
    }

    /**
     * 已计入终态的设备数：回滚后设备状态重置，返回回滚时刻的快照，保证判定依据可解释。
     */
    public int countedReported() {
        return status == BatchStatus.ROLLED_BACK || status == BatchStatus.ROLLING_BACK
                ? rollbackSnapshotReported : reportedCount();
    }

    public int countedSuccess() {
        return status == BatchStatus.ROLLED_BACK || status == BatchStatus.ROLLING_BACK
                ? rollbackSnapshotSuccess : successCount();
    }

    public int countedFailure() {
        return status == BatchStatus.ROLLED_BACK || status == BatchStatus.ROLLING_BACK
                ? rollbackSnapshotFailure : failureCount();
    }

    public double countedFailureRate() {
        return status == BatchStatus.ROLLED_BACK || status == BatchStatus.ROLLING_BACK
                ? rollbackSnapshotFailureRate : failureRate();
    }

    public List<StateEvent> getEvents() {
        return Collections.unmodifiableList(events);
    }

    public DeviceAssignment assignment(String deviceId) {
        return assignments.get(deviceId);
    }

    public List<DeviceAssignment> stageAssignments(int stageIndex) {
        List<DeviceAssignment> list = new ArrayList<>();
        for (DeviceAssignment a : assignments.values()) {
            if (a.getStageIndex() == stageIndex) {
                list.add(a);
            }
        }
        return list;
    }

    public List<DeviceAssignment> activeStageAssignments() {
        return stageAssignments(activeStage);
    }

    // ---------- 计数与判定 ----------

    /** 全量已终态上报的设备数。 */
    public int reportedCount() {
        int n = 0;
        for (DeviceAssignment a : assignments.values()) {
            if (a.getState() == DeviceState.SUCCESS || a.getState() == DeviceState.FAILED) {
                n++;
            }
        }
        return n;
    }

    public int successCount() {
        int n = 0;
        for (DeviceAssignment a : assignments.values()) {
            if (a.getState() == DeviceState.SUCCESS) {
                n++;
            }
        }
        return n;
    }

    public int failureCount() {
        int n = 0;
        for (DeviceAssignment a : assignments.values()) {
            if (a.getState() == DeviceState.FAILED) {
                n++;
            }
        }
        return n;
    }

    /** 全量失败率 = 失败数 / 已终态上报数；尚无上报时为 0。 */
    public double failureRate() {
        int reported = reportedCount();
        return reported == 0 ? 0.0 : (double) failureCount() / reported;
    }

    /** 失败率是否已达到或超过阈值（>= 判定）。 */
    public boolean failureThresholdReached() {
        return reportedCount() > 0 && failureRate() >= failureRateLimit;
    }

    /** 当前阶段是否所有设备都已终态上报。 */
    public boolean activeStageFullyReported() {
        List<DeviceAssignment> list = activeStageAssignments();
        if (list.isEmpty()) {
            return false;
        }
        for (DeviceAssignment a : list) {
            DeviceState s = a.getState();
            if (s != DeviceState.SUCCESS && s != DeviceState.FAILED) {
                return false;
            }
        }
        return true;
    }

    /** 全量设备是否均已终态上报。 */
    public boolean allReported() {
        return reportedCount() == assignments.size();
    }

    // ---------- 状态机 ----------

    /** DRAFT -> ROLLING，并下发第 0 阶段。 */
    public void start(Instant time, java.util.function.Function<String, String> firmwareOf) {
        if (status != BatchStatus.DRAFT) {
            throw new BusinessException(RejectReason.BATCH_NOT_DRAFT,
                    "批次 " + id + " 当前状态 " + status + "，无法开始");
        }
        BatchStatus before = status;
        this.status = BatchStatus.ROLLING;
        this.startedAt = time;
        this.activeStage = 0;
        dispatchStage(0, time, firmwareOf);
        addEvent("BATCH_STARTED", before.name(), status.name(),
                "batch started, stage 0 dispatched at " + time);
    }

    public void pause(String reason, Instant time) {
        if (status != BatchStatus.ROLLING) {
            if (status == BatchStatus.PAUSED) {
                throw new BusinessException(RejectReason.BATCH_ALREADY_PAUSED,
                        "批次 " + id + " 已处于暂停状态");
            }
            throw new BusinessException(RejectReason.BATCH_NOT_ROLLING,
                    "批次 " + id + " 当前状态 " + status + "，无法暂停");
        }
        BatchStatus before = status;
        this.status = BatchStatus.PAUSED;
        this.pausedAt = time;
        this.pauseReason = reason;
        addEvent("BATCH_PAUSED", before.name(), status.name(), "pause reason: " + reason);
    }

    public void resume(Instant time) {
        if (status != BatchStatus.PAUSED) {
            throw new BusinessException(RejectReason.BATCH_NOT_PAUSED,
                    "批次 " + id + " 当前状态 " + status + "，无法恢复");
        }
        BatchStatus before = status;
        this.status = BatchStatus.ROLLING;
        this.pauseReason = null;
        addEvent("BATCH_RESUMED", before.name(), status.name(), "batch resumed at " + time);
    }

    /** 下发指定阶段内尚未下发的设备。 */
    private void dispatchStage(int stageIndex, Instant time,
                               java.util.function.Function<String, String> firmwareOf) {
        int dispatched = 0;
        for (DeviceAssignment a : stageAssignments(stageIndex)) {
            if (!a.isDispatched()) {
                a.markDispatched(time, firmwareOf.apply(a.getDeviceId()));
                dispatched++;
            }
        }
        addEvent("STAGE_DISPATCHED", status.name(), status.name(),
                "stage=" + stageIndex + " newlyDispatched=" + dispatched);
    }

    /**
     * 推进到下一阶段：要求当前阶段全部终态上报，且失败率未达阈值。
     * 最后一阶段全部上报且失败率未达阈值时，批次完成。
     *
     * @return 状态机是否发生了可观察变化
     */
    public boolean advanceIfReady(Instant time,
                                  java.util.function.Function<String, String> firmwareOf) {
        if (status != BatchStatus.ROLLING || activeStage < 0) {
            return false;
        }
        if (!activeStageFullyReported()) {
            return false;
        }
        if (failureThresholdReached()) {
            return false;
        }
        if (activeStage >= stages.size() - 1) {
            BatchStatus before = status;
            this.status = BatchStatus.COMPLETED;
            this.completedAt = time;
            addEvent("BATCH_COMPLETED", before.name(), status.name(),
                    "all " + assignments.size() + " devices reported, failureRate="
                            + String.format("%.4f", failureRate()));
            return true;
        }
        int beforeStage = activeStage;
        this.activeStage++;
        dispatchStage(activeStage, time, firmwareOf);
        addEvent("STAGE_ADVANCED", status.name(), status.name(),
                "stage " + beforeStage + " -> " + activeStage
                        + " success=" + successCount() + " failure=" + failureCount()
                        + " failureRate=" + String.format("%.4f", failureRate()));
        return true;
    }

    public void enterRollingBack(String reason, Instant time) {
        BatchStatus before = status;
        this.status = BatchStatus.ROLLING_BACK;
        this.rollbackReason = reason;
        // 先快照当前计数，随后设备状态会在 finishRollback 中重置为 IDLE
        this.rollbackSnapshotReported = reportedCount();
        this.rollbackSnapshotSuccess = successCount();
        this.rollbackSnapshotFailure = failureCount();
        this.rollbackSnapshotFailureRate = failureRate();
        addEvent("ROLLBACK_TRIGGERED", before.name(), status.name(),
                "rollback reason: " + reason
                        + " | 判定快照 reported=" + rollbackSnapshotReported
                        + " success=" + rollbackSnapshotSuccess
                        + " failure=" + rollbackSnapshotFailure
                        + " failureRate=" + String.format("%.4f", rollbackSnapshotFailureRate)
                        + " threshold=" + String.format("%.2f", failureRateLimit));
    }

    /**
     * 执行回滚：所有非成功且未空闲的设备进入回滚流程并恢复空闲；成功设备也回到空闲
     * （固件版本由服务层处理为旧版本），使全量设备处于可继续操作的一致状态。
     */
    public void finishRollback(Instant time) {
        if (status != BatchStatus.ROLLING_BACK) {
            throw new IllegalStateException("batch " + id + " not in ROLLING_BACK: " + status);
        }
        for (DeviceAssignment a : assignments.values()) {
            if (a.getState() != DeviceState.IDLE) {
                a.enterRollingBack(time);
                a.resetToIdle(time);
            }
        }
        BatchStatus before = status;
        this.status = BatchStatus.ROLLED_BACK;
        this.rolledBackAt = time;
        addEvent("ROLLBACK_FINISHED", before.name(), status.name(),
                "all devices reset to idle; batch is now ROLLED_BACK at " + time);
    }

    public void addEvent(String type, String fromState, String toState, String detail) {
        events.add(StateEvent.of("BATCH", id, type, fromState, toState, detail));
    }
}
