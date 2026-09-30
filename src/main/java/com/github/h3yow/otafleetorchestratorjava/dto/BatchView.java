package com.github.h3yow.otafleetorchestratorjava.dto;

import com.github.h3yow.otafleetorchestratorjava.domain.Batch;
import com.github.h3yow.otafleetorchestratorjava.domain.DeviceAssignment;
import com.github.h3yow.otafleetorchestratorjava.domain.DeviceState;

import java.time.Instant;
import java.util.List;

/** 批次视图：包含状态、各阶段进度、逐设备升级状态与最近事件，供状态一致性核验。 */
public record BatchView(
        String batchId,
        String name,
        String firmwareId,
        String status,
        int activeStage,
        double failureRateLimit,
        double failureRate,
        int totalDevices,
        int successCount,
        int failureCount,
        String pauseReason,
        String rollbackReason,
        Instant createdAt,
        Instant startedAt,
        Instant pausedAt,
        Instant completedAt,
        Instant rolledBackAt,
        List<StageView> stages,
        List<DeviceViewItem> devices,
        List<String> recentEvents
) {

    public record StageView(int index, int percent, int size, int success, int failure,
                            boolean reported, boolean active, boolean dispatched) {
    }

    public record DeviceViewItem(String deviceId, int stage, String state, boolean dispatched,
                                 long lastSeq, String terminalKind, String reason) {
        static DeviceViewItem from(DeviceAssignment a) {
            return new DeviceViewItem(
                    a.getDeviceId(),
                    a.getStageIndex(),
                    a.getState().name(),
                    a.isDispatched(),
                    a.getLastSeq(),
                    a.getTerminalKind() == null ? null : a.getTerminalKind().name(),
                    a.getLastReason());
        }
    }

    public static BatchView from(Batch b, int eventLimit) {
        int stageCount = b.getStages().size();
        int[] success = new int[stageCount];
        int[] failure = new int[stageCount];
        int[] reported = new int[stageCount];
        int[] dispatched = new int[stageCount];
        for (DeviceAssignment a : b.getAssignments().values()) {
            int idx = a.getStageIndex();
            if (a.isDispatched()) {
                dispatched[idx]++;
            }
            DeviceState s = a.getState();
            if (s == DeviceState.SUCCESS) {
                success[idx]++;
                reported[idx]++;
            } else if (s == DeviceState.FAILED) {
                failure[idx]++;
                reported[idx]++;
            }
        }
        List<StageView> stageViews = new java.util.ArrayList<>();
        for (Batch.Stage s : b.getStages()) {
            int i = s.index();
            stageViews.add(new StageView(i, s.percent(), s.size(), success[i], failure[i],
                    reported[i] == s.size(), i == b.getActiveStage(), dispatched[i] == s.size()));
        }
        List<DeviceViewItem> deviceItems = b.getAssignments().values().stream()
                .map(DeviceViewItem::from)
                .toList();
        List<String> events = b.getEvents().stream()
                .skip(Math.max(0, b.getEvents().size() - (long) eventLimit))
                .map(e -> "[" + e.time() + "] " + e.type()
                        + (e.fromState() == null ? "" : " " + e.fromState() + "->" + e.toState())
                        + " | " + e.detail())
                .toList();
        return new BatchView(
                b.getId(),
                b.getName(),
                b.getFirmwareId(),
                b.getStatus().name(),
                b.getActiveStage(),
                b.getFailureRateLimit(),
                b.countedFailureRate(),
                b.getAssignments().size(),
                b.countedSuccess(),
                b.countedFailure(),
                b.getPauseReason(),
                b.getRollbackReason(),
                b.getCreatedAt(),
                b.getStartedAt(),
                b.getPausedAt(),
                b.getCompletedAt(),
                b.getRolledBackAt(),
                List.copyOf(stageViews),
                deviceItems,
                events
        );
    }
}
