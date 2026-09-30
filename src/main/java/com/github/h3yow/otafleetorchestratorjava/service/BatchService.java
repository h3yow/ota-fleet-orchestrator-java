package com.github.h3yow.otafleetorchestratorjava.service;

import com.github.h3yow.otafleetorchestratorjava.domain.Batch;
import com.github.h3yow.otafleetorchestratorjava.domain.BatchStatus;
import com.github.h3yow.otafleetorchestratorjava.domain.Device;
import com.github.h3yow.otafleetorchestratorjava.domain.DeviceAssignment;
import com.github.h3yow.otafleetorchestratorjava.domain.Firmware;
import com.github.h3yow.otafleetorchestratorjava.dto.BatchView;
import com.github.h3yow.otafleetorchestratorjava.dto.CreateBatchRequest;
import com.github.h3yow.otafleetorchestratorjava.error.BusinessException;
import com.github.h3yow.otafleetorchestratorjava.error.RejectReason;
import com.github.h3yow.otafleetorchestratorjava.store.InMemoryStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 升级批次编排：创建、分批推进、暂停/恢复、失败阈值判定与回滚。 */
@Service
public class BatchService {

    private static final Logger log = LoggerFactory.getLogger(BatchService.class);

    private final InMemoryStore store;
    private final FirmwareService firmwareService;

    public BatchService(InMemoryStore store, FirmwareService firmwareService) {
        this.store = store;
        this.firmwareService = firmwareService;
    }

    // ---------- 创建 ----------

    public Batch create(CreateBatchRequest req) {
        if (req == null) {
            throw new BusinessException(RejectReason.VALIDATION_FAILED, "请求体不能为空");
        }
        if (req.firmwareId() == null || req.firmwareId().isBlank()) {
            throw new BusinessException(RejectReason.VALIDATION_FAILED, "firmwareId 不能为空");
        }
        if (req.deviceIds() == null || req.deviceIds().isEmpty()) {
            throw new BusinessException(RejectReason.EMPTY_DEVICE_LIST, "deviceIds 不能为空");
        }
        List<Integer> percents = validateStagePlan(req.stagePercents());
        double limit = req.failureRateLimit() == null ? 0.2 : req.failureRateLimit();
        if (limit <= 0 || limit >= 1) {
            throw new BusinessException(RejectReason.INVALID_STAGE_PLAN,
                    "failureRateLimit 必须在 (0,1) 区间内（1.0 永远无法触发），实际为 " + limit);
        }
        return store.withLock(() -> {
            firmwareService.requireExistsLocked(req.firmwareId());
            // 去重 + 确定性排序：同一份输入永远得到同一套阶段归属
            Set<String> unique = new HashSet<>(req.deviceIds());
            if (unique.size() != req.deviceIds().size()) {
                throw new BusinessException(RejectReason.VALIDATION_FAILED,
                        "deviceIds 存在重复设备");
            }
            List<String> ordered = new ArrayList<>(unique);
            ordered.sort(String::compareTo);
            for (String deviceId : ordered) {
                if (!store.devices().containsKey(deviceId)) {
                    throw new BusinessException(RejectReason.DEVICE_NOT_FOUND,
                            "设备 " + deviceId + " 未注册，无法加入批次");
                }
            }
            String id = store.nextBatchId();
            Batch batch = new Batch(id, req.firmwareId(), req.name(), percents, limit,
                    ordered, Instant.now());
            store.batches().put(id, batch);
            log.info("CREATE_BATCH id={} input=[firmware={}, devices={}, stages={}, limit={}]",
                    id, req.firmwareId(), ordered.size(), percents, limit);
            return batch;
        });
    }

    private List<Integer> validateStagePlan(List<Integer> percents) {
        if (percents == null || percents.isEmpty()) {
            throw new BusinessException(RejectReason.INVALID_STAGE_PLAN,
                    "stagePercents 不能为空，如 [10,30,60]");
        }
        for (Integer p : percents) {
            if (p == null || p <= 0 || p > 100) {
                throw new BusinessException(RejectReason.INVALID_STAGE_PLAN,
                        "每个阶段占比必须在 (0,100] 内，实际为 " + percents);
            }
        }
        int sum = percents.stream().mapToInt(Integer::intValue).sum();
        if (sum != 100) {
            throw new BusinessException(RejectReason.INVALID_STAGE_PLAN,
                    "stagePercents 之和必须为 100，实际为 " + sum + " (" + percents + ")");
        }
        return List.copyOf(percents);
    }

    // ---------- 生命周期操作 ----------

    public Batch start(String batchId) {
        return store.withLock(() -> {
            Batch b = requireBatch(batchId);
            b.start(Instant.now(), this::firmwareVersionOfLocked);
            logLifecycle("START_BATCH", b);
            return b;
        });
    }

    public Batch pause(String batchId, String reason) {
        return store.withLock(() -> {
            Batch b = requireBatch(batchId);
            if (b.getStatus() == BatchStatus.ROLLING_BACK) {
                throw new BusinessException(RejectReason.ROLLBACK_IN_PROGRESS,
                        "批次 " + batchId + " 回滚进行中，不可暂停");
            }
            if (b.getStatus() == BatchStatus.COMPLETED || b.getStatus() == BatchStatus.ROLLED_BACK) {
                throw new BusinessException(RejectReason.BATCH_FINISHED,
                        "批次 " + batchId + " 已终态 " + b.getStatus() + "，不可暂停");
            }
            b.pause(reason == null ? "manual pause" : reason, Instant.now());
            logLifecycle("PAUSE_BATCH", b);
            return b;
        });
    }

    public Batch resume(String batchId) {
        return store.withLock(() -> {
            Batch b = requireBatch(batchId);
            b.resume(Instant.now());
            // 恢复时：暂停期间可能已收齐当前阶段终态上报，尝试自动推进
            boolean advanced = b.advanceIfReady(Instant.now(), this::firmwareVersionOfLocked);
            logLifecycle("RESUME_BATCH", b);
            if (advanced) {
                log.info("RESUME_BATCH auto advanced batch={} -> status={}, activeStage={}",
                        batchId, b.getStatus(), b.getActiveStage());
            }
            return b;
        });
    }

    /** 手动推进：要求批次处于 ROLLING 且当前阶段已全部上报。 */
    public Batch advance(String batchId) {
        return store.withLock(() -> {
            Batch b = requireBatch(batchId);
            if (b.getStatus() != BatchStatus.ROLLING) {
                throw new BusinessException(RejectReason.BATCH_NOT_ROLLING,
                        "批次 " + batchId + " 当前状态 " + b.getStatus() + "，无法推进");
            }
            if (!b.activeStageFullyReported()) {
                throw new BusinessException(RejectReason.STAGE_NOT_READY,
                        "批次 " + batchId + " 当前阶段 " + b.getActiveStage()
                                + " 仍有设备未完成终态上报，无法推进");
            }
            if (b.failureThresholdReached()) {
                // 手动推进同样必须先做失败判定：达到阈值直接进入回滚
                triggerRollbackLocked(b, "失败率达到阈值 "
                        + String.format("%.2f", b.getFailureRateLimit())
                        + "，当前 " + String.format("%.4f", b.failureRate()));
                return b;
            }
            boolean changed = b.advanceIfReady(Instant.now(), this::firmwareVersionOfLocked);
            log.info("ADVANCE_BATCH batch={} changed={} status={} activeStage={}",
                    batchId, changed, b.getStatus(), b.getActiveStage());
            return b;
        });
    }

    public Batch rollback(String batchId, String reason) {
        return store.withLock(() -> {
            Batch b = requireBatch(batchId);
            if (b.getStatus() == BatchStatus.ROLLED_BACK) {
                throw new BusinessException(RejectReason.BATCH_FINISHED,
                        "批次 " + batchId + " 已经回滚完成，不可重复回滚");
            }
            if (b.getStatus() == BatchStatus.COMPLETED) {
                throw new BusinessException(RejectReason.BATCH_FINISHED,
                        "批次 " + batchId + " 已完成，不允许回滚");
            }
            if (b.getStatus() == BatchStatus.ROLLING_BACK) {
                throw new BusinessException(RejectReason.ROLLBACK_IN_PROGRESS,
                        "批次 " + batchId + " 回滚已在进行中");
            }
            triggerRollbackLocked(b, reason == null ? "manual rollback" : reason);
            return b;
        });
    }

    // ---------- 回滚（服务层核心一致性逻辑） ----------

    void triggerRollbackLocked(Batch b, String reason) {
        Instant now = Instant.now();
        log.warn("ROLLBACK_TRIGGER batch={} reason={} failureRate={}/{}",
                b.getId(), reason, String.format("%.4f", b.failureRate()),
                String.format("%.2f", b.getFailureRateLimit()));
        b.enterRollingBack(reason, now);

        Firmware target = store.firmwares().get(b.getFirmwareId());
        String targetVersion = target == null ? null : target.getVersion();

        for (DeviceAssignment a : b.getAssignments().values()) {
            Device device = store.devices().get(a.getDeviceId());
            if (device == null) {
                continue;
            }
            a.enterRollingBack(now);
            // 回滚一致性：曾成功升级到目标版本的设备恢复升级前版本；其余设备版本本来就未变
            restoreDeviceVersionLocked(device, a, targetVersion, now);
            a.resetToIdle(now);
        }
        b.finishRollback(now);
        logLifecycle("ROLLBACK_FINISHED", b);
    }

    private void restoreDeviceVersionLocked(Device device, DeviceAssignment a,
                                            String targetVersion, Instant now) {
        String previous = a.getPreviousFirmwareVersion();
        // 只有曾被标记为成功升级到目标版本的设备才需要把版本恢复；
        // terminalKind=SUCCESS 是"曾成功"的可靠标记。
        if (a.getTerminalKind() == com.github.h3yow.otafleetorchestratorjava.domain.ReportKind.SUCCESS
                && targetVersion != null
                && targetVersion.equals(device.getCurrentFirmwareVersion())) {
            device.markUpgraded(previous, now);
        }
    }

    /**
     * 终态上报后的统一判定（必须在全局锁内调用）：
     * 1) 失败率达到阈值 -> 自动暂停并回滚（无论批次此前是 ROLLING 还是 PAUSED）；
     * 2) 否则在 ROLLING 状态下尝试自动推进阶段/完成；
     * 3) PAUSED 状态下不推进，等待 resume。
     */
    void afterTerminalReportLocked(Batch b) {
        Instant now = Instant.now();
        if (b.failureThresholdReached()) {
            triggerRollbackLocked(b, "失败率达到阈值（自动暂停并回滚）：当前 "
                    + String.format("%.4f", b.failureRate()) + " >= 阈值 "
                    + String.format("%.2f", b.getFailureRateLimit())
                    + "，success=" + b.successCount() + " failure=" + b.failureCount());
            return;
        }
        if (b.getStatus() == BatchStatus.ROLLING) {
            boolean advanced = b.advanceIfReady(now, this::firmwareVersionOfLocked);
            if (advanced) {
                log.info("AUTO_ADVANCE batch={} status={} activeStage={} success={} failure={}",
                        b.getId(), b.getStatus(), b.getActiveStage(),
                        b.successCount(), b.failureCount());
            }
        }
    }

    // ---------- 查询 ----------

    public Batch requireBatch(String batchId) {
        Batch b = store.batches().get(batchId);
        if (b == null) {
            throw new BusinessException(RejectReason.BATCH_NOT_FOUND,
                    "批次 " + batchId + " 不存在");
        }
        return b;
    }

    public BatchView view(String batchId, int eventLimit) {
        return store.withLock(() -> BatchView.from(requireBatch(batchId), eventLimit));
    }

    public List<BatchView> list(int eventLimit) {
        return store.withLock(() -> store.batches().values().stream()
                .map(b -> BatchView.from(b, eventLimit))
                .toList());
    }

    private String firmwareVersionOfLocked(String deviceId) {
        Device d = store.devices().get(deviceId);
        return d == null ? null : d.getCurrentFirmwareVersion();
    }

    private void logLifecycle(String action, Batch b) {
        log.info("{} batch={} status={} activeStage={} success={} failure={} failureRate={}",
                action, b.getId(), b.getStatus(), b.getActiveStage(),
                b.countedSuccess(), b.countedFailure(),
                String.format("%.4f", b.countedFailureRate()));
    }
}
