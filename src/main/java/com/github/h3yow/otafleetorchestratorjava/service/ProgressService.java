package com.github.h3yow.otafleetorchestratorjava.service;

import com.github.h3yow.otafleetorchestratorjava.domain.enums.BatchStatus;
import com.github.h3yow.otafleetorchestratorjava.domain.enums.DeviceUpgradeStatus;
import com.github.h3yow.otafleetorchestratorjava.domain.enums.PauseReason;
import com.github.h3yow.otafleetorchestratorjava.dto.request.ProgressReportRequest;
import com.github.h3yow.otafleetorchestratorjava.dto.request.ReportResult;
import com.github.h3yow.otafleetorchestratorjava.exception.DomainException;
import com.github.h3yow.otafleetorchestratorjava.exception.ErrorCode;
import com.github.h3yow.otafleetorchestratorjava.model.Device;
import com.github.h3yow.otafleetorchestratorjava.model.DeviceUpgrade;
import com.github.h3yow.otafleetorchestratorjava.model.IdempotentReportRecord;
import com.github.h3yow.otafleetorchestratorjava.model.UpgradeBatch;
import com.github.h3yow.otafleetorchestratorjava.store.InMemoryStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;

@Service
public class ProgressService {

    private static final Logger log = LoggerFactory.getLogger(ProgressService.class);

    /**
     * 设备升级状态机：只允许向表中的后继状态迁移。
     * SUCCESS/FAILED/ROLLED_BACK 为终态，没有后继。
     */
    private static final Map<DeviceUpgradeStatus, DeviceUpgradeStatus> NEXT =
            new EnumMap<>(DeviceUpgradeStatus.class);

    static {
        NEXT.put(DeviceUpgradeStatus.PENDING, DeviceUpgradeStatus.DOWNLOADING);
        NEXT.put(DeviceUpgradeStatus.DOWNLOADING, DeviceUpgradeStatus.INSTALLING);
        NEXT.put(DeviceUpgradeStatus.INSTALLING, DeviceUpgradeStatus.SUCCESS);
    }

    private final InMemoryStore store;

    public ProgressService(InMemoryStore store) {
        this.store = store;
    }

    /**
     * 处理设备进度上报。同一批次的全部判定在批次锁内原子完成。
     * 判定顺序：
     * 1) 参数合法性；
     * 2) 幂等：deviceId+requestId 命中已处理记录 -> 直接返回首次结果（duplicate=true）；
     * 3) 设备 / 批次 / 下发关系存在性与批次终态；
     * 4) 乱序：seq 小于已处理最大 seq -> STALE_SEQUENCE；
     * 5) 状态机：必须是合法后继；同状态良性重传返回当前状态但不计数；回滚后上报拒绝；
     * 6) 应用迁移、更新计数、判定失败阈值自动暂停，最后落幂等记录。
     */
    public ReportResult report(String deviceId, ProgressReportRequest request) {
        if (deviceId == null || deviceId.isBlank()) {
            throw new DomainException(ErrorCode.INVALID_REQUEST, "deviceId 不能为空");
        }
        if (request == null || request.batchId() == null || request.status() == null
                || request.seq() == null || request.requestId() == null) {
            throw new DomainException(ErrorCode.INVALID_REQUEST,
                    "batchId / status / seq / requestId 均不能为空");
        }
        DeviceUpgradeStatus reported = request.status();
        if (reported != DeviceUpgradeStatus.DOWNLOADING
                && reported != DeviceUpgradeStatus.INSTALLING
                && reported != DeviceUpgradeStatus.SUCCESS
                && reported != DeviceUpgradeStatus.FAILED) {
            throw new DomainException(ErrorCode.INVALID_REQUEST,
                    "上报状态只允许 DOWNLOADING/INSTALLING/SUCCESS/FAILED，收到=" + reported);
        }

        log.info("[progress-in] deviceId={}, batchId={}, reportedStatus={}, seq={}, requestId={}",
                deviceId, request.batchId(), reported, request.seq(), request.requestId());

        // 幂等检查与处理放在同一把锁内（见 doReport），保证"检查-状态迁移-计数-落幂等记录"原子，
        // 避免并发线程都在加锁前读到"未处理"而重复计数。
        Device device = store.findDevice(deviceId)
                .orElseThrow(() -> new DomainException(ErrorCode.DEVICE_NOT_FOUND,
                        "设备不存在: " + deviceId));
        UpgradeBatch batch = store.findBatch(request.batchId())
                .orElseThrow(() -> new DomainException(ErrorCode.BATCH_NOT_FOUND,
                        "批次不存在: " + request.batchId()));

        return store.withBatchLock(request.batchId(), () ->
                store.withDeviceLock(deviceId, () ->
                        doReport(device, batch, request, reported)));
    }

    private ReportResult doReport(Device device, UpgradeBatch batch,
                                  ProgressReportRequest request, DeviceUpgradeStatus reported) {
        String deviceId = device.getId();

        // 幂等：锁内检查，命中直接回放首次结果，不重复迁移/计数
        IdempotentReportRecord existing =
                store.findProcessedReport(deviceId, request.requestId()).orElse(null);
        if (existing != null) {
            if (!existing.getBatchId().equals(request.batchId())) {
                throw new DomainException(ErrorCode.DEVICE_NOT_IN_BATCH,
                        "requestId=" + request.requestId() + " 已用于其它批次 " + existing.getBatchId());
            }
            log.info("[progress-idempotent-hit] deviceId={}, requestId={}, replayStatus={}, 不重复计数/推进",
                    deviceId, request.requestId(), existing.getResultingStatus());
            return buildResult(deviceId, request, existing.getResultingStatus(), true,
                    existing.isTriggeredAutoPause(), "IDEMPOTENT_REPLAY");
        }

        DeviceUpgrade upgrade = store.findUpgrade(batch.getId(), deviceId).orElse(null);
        if (upgrade == null) {
            throw new DomainException(ErrorCode.DEVICE_NOT_IN_BATCH,
                    "设备 " + deviceId + " 未被批次 " + batch.getId() + " 下发");
        }
        if (batch.getStatus() == BatchStatus.ROLLED_BACK) {
            throw new DomainException(ErrorCode.REPORT_REJECTED_TERMINAL,
                    "批次已回滚，拒绝上报: " + batch.getId());
        }
        if (batch.getStatus() == BatchStatus.COMPLETED) {
            throw new DomainException(ErrorCode.REPORT_REJECTED_TERMINAL,
                    "批次已完成，拒绝上报: " + batch.getId());
        }
        DeviceUpgradeStatus current = upgrade.getStatus();
        if (current == DeviceUpgradeStatus.ROLLED_BACK) {
            throw new DomainException(ErrorCode.REPORT_REJECTED_TERMINAL,
                    "设备升级记录已回滚(ROLLED_BACK)，拒绝上报");
        }

        // 乱序：seq 必须严格大于已接受的最大 seq
        if (request.seq() < upgrade.getLastSeq()) {
            throw new DomainException(ErrorCode.STALE_SEQUENCE,
                    "过期请求: seq=" + request.seq() + " < lastSeq=" + upgrade.getLastSeq());
        }

        // 失败可在任意非终态上报（设备安装失败等）；成功/进度态必须符合状态机
        if (reported == DeviceUpgradeStatus.FAILED) {
            if (current == DeviceUpgradeStatus.FAILED) {
                // 同状态良性重传（不同 requestId）：返回当前状态，不重复计数
                log.info("[progress-benign-retry] deviceId={}, status=FAILED 已是失败终态，忽略重复失败上报",
                        deviceId);
                return buildResult(deviceId, request, current, false,
                        batch.getStatus() == BatchStatus.PAUSED && batch.getPauseReason() == PauseReason.FAILURE_THRESHOLD,
                        "BENIGN_SAME_STATE");
            }
            if (current == DeviceUpgradeStatus.SUCCESS) {
                throw new DomainException(ErrorCode.ILLEGAL_STATUS_TRANSITION,
                        "设备已 SUCCESS，不能回退为 FAILED（状态不允许倒退）");
            }
        } else {
            DeviceUpgradeStatus expected = NEXT.get(current);
            if (current == reported) {
                // 同状态良性重传（如下游重发 DOWNLOADING）：幂等地返回当前状态，不计数
                log.info("[progress-benign-retry] deviceId={}, status={} 重复上报同状态，忽略",
                        deviceId, current);
                return buildResult(deviceId, request, current, false,
                        batch.getStatus() == BatchStatus.PAUSED
                                && batch.getPauseReason() == PauseReason.FAILURE_THRESHOLD,
                        "BENIGN_SAME_STATE");
            }
            if (current == DeviceUpgradeStatus.SUCCESS || current == DeviceUpgradeStatus.FAILED) {
                throw new DomainException(ErrorCode.ILLEGAL_STATUS_TRANSITION,
                        "设备已处于终态 " + current + "，不能再迁移到 " + reported);
            }
            if (reported != expected) {
                throw new DomainException(ErrorCode.ILLEGAL_STATUS_TRANSITION,
                        "非法状态迁移: " + current + " -> " + reported + "，期望的下一状态=" + expected);
            }
        }

        // 通过全部校验：应用迁移
        DeviceUpgradeStatus from = current;
        Instant now = Instant.now();
        upgrade.setStatus(reported);
        upgrade.setLastSeq(request.seq());
        upgrade.setUpdatedAt(now);

        boolean triggeredAutoPause = false;
        String decision = "ACCEPTED";
        if (reported == DeviceUpgradeStatus.SUCCESS) {
            batch.setSuccessCount(batch.getSuccessCount() + 1);
            device.setCurrentFirmwareVersion(batch.getTargetVersion());
            device.setTargetBatchId(null);
        } else if (reported == DeviceUpgradeStatus.FAILED) {
            batch.setFailedCount(batch.getFailedCount() + 1);
            device.setTargetBatchId(null);
            // 失败率判定：失败数 / 已下发数，严格大于阈值则自动暂停（已暂停则不重复触发）
            int dispatched = batch.getDispatchedCount();
            double failureRate = dispatched == 0 ? 0d : (batch.getFailedCount() * 100.0 / dispatched);
            if (batch.getStatus() == BatchStatus.ACTIVE
                    && failureRate > batch.getFailureThresholdPercent()) {
                batch.setStatus(BatchStatus.PAUSED);
                batch.setPauseReason(PauseReason.FAILURE_THRESHOLD);
                batch.setPausedAt(now);
                triggeredAutoPause = true;
                decision = "AUTO_PAUSED_FAILURE_THRESHOLD";
                log.warn("[batch-auto-pause] batchId={}, failed={}, dispatched={}, failureRate={}% > threshold={}% -> PAUSED(FAILURE_THRESHOLD)",
                        batch.getId(), batch.getFailedCount(), dispatched,
                        String.format("%.1f", failureRate), batch.getFailureThresholdPercent());
            }
        }

        log.info("[progress-apply] deviceId={}, transition={}->{}, seq={}, requestId={}, batch={}, success={}, failed={}, decision={}",
                deviceId, from, reported, request.seq(), request.requestId(),
                batch.getStatus(), batch.getSuccessCount(), batch.getFailedCount(), decision);

        // 落幂等记录（仅对被接受并产生迁移的上报）
        store.saveProcessedReport(deviceId, request.requestId(),
                new IdempotentReportRecord(batch.getId(), reported, request.seq(),
                        triggeredAutoPause, now));

        return buildResult(deviceId, request, reported, false, triggeredAutoPause, decision);
    }

    private ReportResult buildResult(String deviceId, ProgressReportRequest request,
                                     DeviceUpgradeStatus deviceStatus, boolean duplicate,
                                     boolean triggeredAutoPause, String decision) {
        UpgradeBatch batch = store.findBatch(request.batchId()).orElse(null);
        long success = batch == null ? 0 : batch.getSuccessCount();
        long failed = batch == null ? 0 : batch.getFailedCount();
        BatchStatus batchStatus = batch == null ? null : batch.getStatus();
        return new ReportResult(deviceId, request.batchId(), request.requestId(),
                deviceStatus, batchStatus, duplicate, triggeredAutoPause,
                success, failed, decision);
    }
}
