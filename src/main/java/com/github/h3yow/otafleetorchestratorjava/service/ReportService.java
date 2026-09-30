package com.github.h3yow.otafleetorchestratorjava.service;

import com.github.h3yow.otafleetorchestratorjava.domain.Batch;
import com.github.h3yow.otafleetorchestratorjava.domain.BatchStatus;
import com.github.h3yow.otafleetorchestratorjava.domain.Device;
import com.github.h3yow.otafleetorchestratorjava.domain.DeviceAssignment;
import com.github.h3yow.otafleetorchestratorjava.domain.DeviceState;
import com.github.h3yow.otafleetorchestratorjava.domain.Firmware;
import com.github.h3yow.otafleetorchestratorjava.domain.ReportKind;
import com.github.h3yow.otafleetorchestratorjava.dto.ReportRequest;
import com.github.h3yow.otafleetorchestratorjava.error.BusinessException;
import com.github.h3yow.otafleetorchestratorjava.error.RejectReason;
import com.github.h3yow.otafleetorchestratorjava.store.InMemoryStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

/**
 * 设备升级进度上报处理。
 *
 * <p>幂等/乱序规则（在全局锁内判定）：
 * <ul>
 *   <li>seq 小于已接受的最大 seq -> STALE_REQUEST（过期请求，拒绝）；</li>
 *   <li>seq 相同且终态结果一致 -> DUPLICATE_TERMINAL_REPORT（幂等成功但不重复计数）；</li>
 *   <li>seq 相同但内容不同 -> ILLEGAL_STATE_TRANSITION（冲突重试，拒绝）；</li>
 *   <li>状态只能沿 IDLE/PENDING -> DOWNLOADING -> INSTALLING -> SUCCESS/FAILED 前进，
 *       非前进迁移 -> ILLEGAL_STATE_TRANSITION（FAILED 已终态，任何后续上报均拒绝）；</li>
 *   <li>终态判定 / 阶段推进 / 自动回滚与计数在同一临界区内完成，并发上报不会重复推进或漏判。</li>
 * </ul>
 */
@Service
public class ReportService {

    private static final Logger log = LoggerFactory.getLogger(ReportService.class);

    /** 非终态进度的合法后继关系。 */
    private static final Map<DeviceState, Set<DeviceState>> FORWARD_TRANSITIONS = Map.of(
            DeviceState.IDLE, Set.of(DeviceState.DOWNLOADING, DeviceState.INSTALLING,
                    DeviceState.SUCCESS, DeviceState.FAILED),
            DeviceState.PENDING, Set.of(DeviceState.DOWNLOADING, DeviceState.INSTALLING,
                    DeviceState.SUCCESS, DeviceState.FAILED),
            DeviceState.DOWNLOADING, Set.of(DeviceState.INSTALLING,
                    DeviceState.SUCCESS, DeviceState.FAILED),
            DeviceState.INSTALLING, Set.of(DeviceState.SUCCESS, DeviceState.FAILED)
    );

    private final InMemoryStore store;
    private final BatchService batchService;

    public ReportService(InMemoryStore store, BatchService batchService) {
        this.store = store;
        this.batchService = batchService;
    }

    /** 上报处理结果：accepted=false 表示幂等重复，未产生状态变化。 */
    public record ReportOutcome(boolean accepted, boolean duplicate, String deviceState,
                                String batchStatus, boolean stateChanged, int activeStage) {
    }

    public ReportOutcome report(String batchId, String deviceId, ReportRequest req) {
        if (req == null || req.seq() == null || req.status() == null) {
            throw new BusinessException(RejectReason.VALIDATION_FAILED,
                    "seq 与 status 均为必填项");
        }
        final long seq = req.seq();
        final ReportKind kind;
        try {
            kind = ReportKind.valueOf(req.status().trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BusinessException(RejectReason.VALIDATION_FAILED,
                    "未知的上报状态: " + req.status()
                            + "，合法值为 DOWNLOADING/INSTALLING/SUCCESS/FAILED");
        }

        return store.withLock(() -> {
            Batch batch = batchService.requireBatch(batchId);
            Device device = store.devices().get(deviceId);
            if (device == null) {
                throw new BusinessException(RejectReason.DEVICE_NOT_FOUND,
                        "设备 " + deviceId + " 未注册");
            }
            DeviceAssignment a = batch.assignment(deviceId);
            if (a == null) {
                throw new BusinessException(RejectReason.DEVICE_NOT_ASSIGNED,
                        "设备 " + deviceId + " 不属于批次 " + batchId);
            }

            BatchStatus bs = batch.getStatus();
            log.info("REPORT_INPUT batch={} device={} seq={} status={} reason={} | batch={} deviceState={} lastSeq={} activeStage={} assignmentStage={}",
                    batchId, deviceId, seq, kind, req.reason(), bs, a.getState(),
                    a.getLastSeq(), batch.getActiveStage(), a.getStageIndex());

            if (bs == BatchStatus.ROLLING_BACK) {
                throw new BusinessException(RejectReason.ROLLBACK_IN_PROGRESS,
                        "批次 " + batchId + " 回滚进行中，拒绝上报");
            }
            if (bs == BatchStatus.COMPLETED || bs == BatchStatus.ROLLED_BACK) {
                throw new BusinessException(RejectReason.BATCH_FINISHED,
                        "批次 " + batchId + " 已终态 " + bs + "，拒绝上报");
            }

            // 阶段归属：只允许当前激活阶段的设备上报（已完成阶段的设备已终态，重复走幂等分支）
            if (a.getStageIndex() != batch.getActiveStage()
                    && !isTerminalState(a.getState())) {
                throw new BusinessException(RejectReason.STAGE_MISMATCH,
                        "设备 " + deviceId + " 属于阶段 " + a.getStageIndex()
                                + "，当前激活阶段为 " + batch.getActiveStage() + "，暂不接收其上报");
            }

            // 已终态：仅接受相同 seq + 相同结果的幂等重放
            if (isTerminalState(a.getState())) {
                if (seq == a.getLastSeq() && a.getTerminalKind() == kind) {
                    log.info("REPORT_IDEMPOTENT batch={} device={} seq={} kind={} -> 重复终态上报，幂等返回，不重复计数",
                            batchId, deviceId, seq, kind);
                    return new ReportOutcome(true, true, a.getState().name(),
                            batch.getStatus().name(), false, batch.getActiveStage());
                }
                throw new BusinessException(RejectReason.ILLEGAL_STATE_TRANSITION,
                        "设备 " + deviceId + " 已处于终态 " + a.getState()
                                + "，拒绝新的上报 kind=" + kind + " seq=" + seq
                                + "（终态不可逆，防止状态回退）");
            }

            // seq 乱序判定
            if (seq < a.getLastSeq()) {
                throw new BusinessException(RejectReason.STALE_REQUEST,
                        "设备 " + deviceId + " 上报 seq=" + seq + " 小于已接受 seq="
                                + a.getLastSeq() + "，过期请求被拒绝");
            }
            if (seq == a.getLastSeq()) {
                throw new BusinessException(RejectReason.ILLEGAL_STATE_TRANSITION,
                        "设备 " + deviceId + " 重复使用 seq=" + seq
                                + " 但内容不同（已记录状态 " + a.getState() + "），冲突请求被拒绝");
            }

            // 暂停期间：非终态进度拒绝（阶段未激活）；终态结果仍接收并计数
            DeviceState target = stateOf(kind);
            if (bs == BatchStatus.PAUSED && target != DeviceState.SUCCESS
                    && target != DeviceState.FAILED) {
                throw new BusinessException(RejectReason.BATCH_PAUSED,
                        "批次 " + batchId + " 已暂停，阶段 " + batch.getActiveStage()
                                + " 未激活，仅接受终态上报");
            }
            if (!a.isDispatched()) {
                throw new BusinessException(RejectReason.STAGE_NOT_ACTIVE,
                        "设备 " + deviceId + " 尚未收到下发任务，不能上报");
            }

            // 前进迁移校验，防止 DOWNLOADING 等乱序回退
            Set<DeviceState> allowed = FORWARD_TRANSITIONS.get(a.getState());
            if (allowed == null || !allowed.contains(target)) {
                throw new BusinessException(RejectReason.ILLEGAL_STATE_TRANSITION,
                        "设备 " + deviceId + " 状态 " + a.getState() + " -> " + target
                                + " 不是合法前进迁移，乱序上报被拒绝");
            }

            boolean changed = a.applyReport(kind, seq, req.reason(), Instant.now());
            device.heartbeat(Instant.now());

            if (kind == ReportKind.SUCCESS) {
                Firmware fw = store.firmwares().get(batch.getFirmwareId());
                if (fw != null) {
                    device.markUpgraded(fw.getVersion(), Instant.now());
                }
            }

            boolean terminal = kind == ReportKind.SUCCESS || kind == ReportKind.FAILED;
            if (terminal) {
                log.info("REPORT_TERMINAL batch={} device={} kind={} -> 判定依据: reported={}, success={}, failure={}, failureRate={}, threshold={}",
                        batchId, deviceId, kind, batch.reportedCount(), batch.successCount(),
                        batch.failureCount(), String.format("%.4f", batch.failureRate()),
                        String.format("%.2f", batch.getFailureRateLimit()));
                // 与计数同一临界区：阈值判定 + 自动推进/回滚整体原子
                batchService.afterTerminalReportLocked(batch);
            } else {
                log.info("REPORT_PROGRESS batch={} device={} kind={} state={} (不计入成功/失败)",
                        batchId, deviceId, kind, a.getState());
            }

            return new ReportOutcome(true, false, a.getState().name(),
                    batch.getStatus().name(), changed, batch.getActiveStage());
        });
    }

    private boolean isTerminalState(DeviceState s) {
        return s == DeviceState.SUCCESS || s == DeviceState.FAILED
                || s == DeviceState.ROLLBACK_FAILED;
    }

    private DeviceState stateOf(ReportKind kind) {
        return switch (kind) {
            case DOWNLOADING -> DeviceState.DOWNLOADING;
            case INSTALLING -> DeviceState.INSTALLING;
            case SUCCESS -> DeviceState.SUCCESS;
            case FAILED -> DeviceState.FAILED;
        };
    }
}
