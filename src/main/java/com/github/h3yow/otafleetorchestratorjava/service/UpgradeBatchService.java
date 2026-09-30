package com.github.h3yow.otafleetorchestratorjava.service;

import com.github.h3yow.otafleetorchestratorjava.domain.enums.BatchStatus;
import com.github.h3yow.otafleetorchestratorjava.domain.enums.DeviceUpgradeStatus;
import com.github.h3yow.otafleetorchestratorjava.domain.enums.FirmwareStatus;
import com.github.h3yow.otafleetorchestratorjava.domain.enums.PauseReason;
import com.github.h3yow.otafleetorchestratorjava.dto.request.CreateBatchRequest;
import com.github.h3yow.otafleetorchestratorjava.exception.DomainException;
import com.github.h3yow.otafleetorchestratorjava.exception.ErrorCode;
import com.github.h3yow.otafleetorchestratorjava.model.Device;
import com.github.h3yow.otafleetorchestratorjava.model.DeviceUpgrade;
import com.github.h3yow.otafleetorchestratorjava.model.Firmware;
import com.github.h3yow.otafleetorchestratorjava.model.UpgradeBatch;
import com.github.h3yow.otafleetorchestratorjava.store.InMemoryStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

@Service
public class UpgradeBatchService {

    private static final Logger log = LoggerFactory.getLogger(UpgradeBatchService.class);
    private static final int DEFAULT_BATCH_SIZE = 10;
    private static final int DEFAULT_FAILURE_THRESHOLD_PERCENT = 50;

    private final InMemoryStore store;

    public UpgradeBatchService(InMemoryStore store) {
        this.store = store;
    }

    /**
     * 创建升级批次（初始 PENDING，尚未下发）。
     * 前置条件：
     * - 固件存在且已 RELEASED；
     * - batchSize 为正、失败阈值在 0..100；
     * - 指定的设备必须都存在；
     * - 至少有一台当前版本 != 目标版本的设备可升级。
     * 合格设备快照在建批次时固化（按 deviceId 排序），后续推进不再受设备列表变化影响。
     */
    public UpgradeBatch create(CreateBatchRequest request) {
        if (request == null || request.firmwareId() == null || request.firmwareId().isBlank()) {
            throw new DomainException(ErrorCode.INVALID_REQUEST, "firmwareId 不能为空");
        }
        Firmware firmware = store.findFirmware(request.firmwareId())
                .orElseThrow(() -> new DomainException(ErrorCode.FIRMWARE_NOT_FOUND,
                        "固件不存在: " + request.firmwareId()));
        if (firmware.getStatus() != FirmwareStatus.RELEASED) {
            throw new DomainException(ErrorCode.FIRMWARE_NOT_RELEASED,
                    "固件尚未发布，不能建批次: " + firmware.getId());
        }
        int batchSize = request.batchSize() == null ? DEFAULT_BATCH_SIZE : request.batchSize();
        if (batchSize <= 0) {
            throw new DomainException(ErrorCode.INVALID_REQUEST, "batchSize 必须为正整数");
        }
        int threshold = request.failureThresholdPercent() == null
                ? DEFAULT_FAILURE_THRESHOLD_PERCENT : request.failureThresholdPercent();
        if (threshold < 0 || threshold > 100) {
            throw new DomainException(ErrorCode.INVALID_REQUEST, "failureThresholdPercent 必须在 0..100 之间");
        }

        List<Device> candidates;
        if (request.deviceIds() == null) {
            candidates = store.listDevices();
        } else {
            candidates = new ArrayList<>();
            for (String deviceId : request.deviceIds()) {
                Device device = store.findDevice(deviceId)
                        .orElseThrow(() -> new DomainException(ErrorCode.DEVICE_NOT_FOUND,
                                "设备不存在: " + deviceId));
                candidates.add(device);
            }
        }
        List<String> eligible = candidates.stream()
                .filter(d -> !firmware.getVersion().equals(d.getCurrentFirmwareVersion()))
                .map(Device::getId)
                .distinct()
                .sorted()
                .toList();
        if (eligible.isEmpty()) {
            throw new DomainException(ErrorCode.BATCH_NO_ELIGIBLE_DEVICE,
                    "没有需要升级到 " + firmware.getVersion() + " 的设备");
        }

        UpgradeBatch batch = new UpgradeBatch(
                UUID.randomUUID().toString(),
                firmware.getId(),
                null,
                firmware.getVersion(),
                batchSize,
                threshold,
                eligible,
                Instant.now());
        store.putBatch(batch);
        log.info("[batch-create] firmwareId={}, target={}, batchSize={}, threshold={}%, eligible={} -> batchId={}, status=PENDING",
                firmware.getId(), firmware.getVersion(), batchSize, threshold, eligible, batch.getId());
        return batch;
    }

    /**
     * 分批推进。
     * PENDING/ACTIVE 允许调用：
     * - 当前一批仍有在途设备（PENDING/DOWNLOADING/INSTALLING）时拒绝，避免跨批重复下发；
     * - 取游标之后的 batchSize 台设备下发（PENDING 升级记录 + 绑定 targetBatchId）；
     * - 已全部下发且全部终态时，批次自动 COMPLETED。
     */
    public UpgradeBatch advance(String batchId) {
        UpgradeBatch batch = requireBatch(batchId);
        return store.withBatchLock(batchId, () -> {
            if (isTerminal(batch.getStatus())) {
                throw new DomainException(ErrorCode.BATCH_ALREADY_TERMINAL,
                        "批次已终态(" + batch.getStatus() + ")，不能推进: " + batchId);
            }
            if (batch.getStatus() == BatchStatus.PAUSED) {
                throw new DomainException(ErrorCode.BATCH_NOT_ACTIVE,
                        "批次处于 PAUSED，请先 resume 再推进: " + batchId);
            }

            int inflight = countInflight(batchId);
            if (batch.getCurrentStage() >= 0 && inflight > 0) {
                throw new DomainException(ErrorCode.BATCH_HAS_INFLIGHT_DEVICES,
                        "当前第 " + batch.getCurrentStage() + " 批仍有 " + inflight + " 台在途设备，不能推进下一批");
            }

            List<String> eligible = batch.getEligibleDeviceIds();
            int from = batch.getDispatchedCount();
            if (from >= eligible.size()) {
                // 全部下发完毕且无在途：收尾
                batch.setStatus(BatchStatus.COMPLETED);
                batch.setCompletedAt(Instant.now());
                log.info("[batch-complete] batchId={}, success={}, failed={}",
                        batchId, batch.getSuccessCount(), batch.getFailedCount());
                return batch;
            }

            int to = Math.min(from + batch.getBatchSize(), eligible.size());
            List<String> slice = eligible.subList(from, to);
            Instant now = Instant.now();
            List<String> dispatched = new ArrayList<>();
            for (String deviceId : slice) {
                Device device = store.findDevice(deviceId).orElse(null);
                if (device == null) {
                    continue;
                }
                // 锁顺序固定：批次锁 -> 设备锁，避免死循环等待
                store.withDeviceLock(deviceId, (Runnable) () -> {
                    if (device.getTargetBatchId() != null
                            && !batchId.equals(device.getTargetBatchId())) {
                        log.warn("[batch-dispatch-skip] deviceId={} 已在其它批次 {} 中，本次跳过",
                                deviceId, device.getTargetBatchId());
                        return;
                    }
                    DeviceUpgrade upgrade = new DeviceUpgrade(
                            deviceId, batchId, device.getCurrentFirmwareVersion(),
                            batch.getTargetVersion(), now);
                    store.putUpgrade(upgrade);
                    device.setTargetBatchId(batchId);
                });
                dispatched.add(deviceId);
            }

            int nextStage = batch.getCurrentStage() + 1;
            batch.setCurrentStage(nextStage);
            batch.setDispatchedCount(to);
            batch.setStatus(BatchStatus.ACTIVE);
            log.info("[batch-advance] batchId={}, stage={}, dispatched={} (devices={}), totalDispatched={}/{}",
                    batchId, nextStage, dispatched.size(), dispatched, to, eligible.size());
            return batch;
        });
    }

    /** 人工暂停：仅 ACTIVE 可暂停。 */
    public UpgradeBatch pause(String batchId) {
        UpgradeBatch batch = requireBatch(batchId);
        return store.withBatchLock(batchId, () -> {
            if (isTerminal(batch.getStatus())) {
                throw new DomainException(ErrorCode.BATCH_ALREADY_TERMINAL,
                        "批次已终态(" + batch.getStatus() + ")，不能暂停: " + batchId);
            }
            if (batch.getStatus() != BatchStatus.ACTIVE) {
                throw new DomainException(ErrorCode.BATCH_NOT_PAUSABLE,
                        "只有 ACTIVE 批次可以暂停，当前状态=" + batch.getStatus());
            }
            batch.setStatus(BatchStatus.PAUSED);
            batch.setPauseReason(PauseReason.MANUAL);
            batch.setPausedAt(Instant.now());
            log.info("[batch-pause] batchId={}, reason=MANUAL", batchId);
            return batch;
        });
    }

    /** 恢复：仅 PAUSED 可恢复（无论人工还是自动暂停）。 */
    public UpgradeBatch resume(String batchId) {
        UpgradeBatch batch = requireBatch(batchId);
        return store.withBatchLock(batchId, () -> {
            if (batch.getStatus() != BatchStatus.PAUSED) {
                throw new DomainException(ErrorCode.BATCH_NOT_PAUSED,
                        "批次未处于 PAUSED，不能恢复，当前状态=" + batch.getStatus());
            }
            log.info("[batch-resume] batchId={}, previousPauseReason={}", batchId, batch.getPauseReason());
            batch.setStatus(BatchStatus.ACTIVE);
            batch.setPauseReason(null);
            batch.setPausedAt(null);
            return batch;
        });
    }

    /**
     * 回滚：ACTIVE/PAUSED 可回滚，COMPLETED/ROLLED_BACK 拒绝。
     * 对批次内所有已下发设备：
     * - 设备版本恢复为其升级前版本 fromVersion（无论成功/失败/在途，统一切回，保证一致可解释）；
     * - 升级记录置 ROLLED_BACK，清空 targetBatchId，设备可被新批次重新选中；
     * - 批次置 ROLLED_BACK（终态，不可再推进/暂停/恢复）。
     */
    public UpgradeBatch rollback(String batchId, String reason) {
        UpgradeBatch batch = requireBatch(batchId);
        return store.withBatchLock(batchId, () -> {
            if (batch.getStatus() == BatchStatus.ROLLED_BACK) {
                throw new DomainException(ErrorCode.BATCH_ALREADY_TERMINAL,
                        "批次已回滚，不能重复回滚: " + batchId);
            }
            if (batch.getStatus() == BatchStatus.COMPLETED) {
                throw new DomainException(ErrorCode.BATCH_ALREADY_TERMINAL,
                        "批次已完成，不能回滚: " + batchId);
            }
            if (batch.getStatus() != BatchStatus.ACTIVE && batch.getStatus() != BatchStatus.PAUSED) {
                throw new DomainException(ErrorCode.BATCH_NOT_ROLLBACKABLE,
                        "只有 ACTIVE/PAUSED 批次可以回滚，当前状态=" + batch.getStatus());
            }

            List<DeviceUpgrade> upgrades = store.listUpgradesOfBatch(batchId);
            for (DeviceUpgrade upgrade : upgrades) {
                Device device = store.findDevice(upgrade.getDeviceId()).orElse(null);
                if (device == null) {
                    continue;
                }
                store.withDeviceLock(upgrade.getDeviceId(), (Runnable) () -> {
                    device.setCurrentFirmwareVersion(upgrade.getFromVersion());
                    device.setTargetBatchId(null);
                });
                upgrade.setStatus(DeviceUpgradeStatus.ROLLED_BACK);
                upgrade.setUpdatedAt(Instant.now());
            }
            batch.setStatus(BatchStatus.ROLLED_BACK);
            batch.setRolledBackAt(Instant.now());
            log.info("[batch-rollback] batchId={}, reason={}, rolledBackDevices={}, batchFinalStatus=ROLLED_BACK",
                    batchId, reason, upgrades.size());
            return batch;
        });
    }

    public UpgradeBatch get(String batchId) {
        return requireBatch(batchId);
    }

    public List<UpgradeBatch> list() {
        return store.listBatches().stream()
                .sorted(Comparator.comparing(UpgradeBatch::getCreatedAt))
                .toList();
    }

    // ---------------- 内部工具 ----------------

    private UpgradeBatch requireBatch(String batchId) {
        return store.findBatch(batchId)
                .orElseThrow(() -> new DomainException(ErrorCode.BATCH_NOT_FOUND,
                        "批次不存在: " + batchId));
    }

    private boolean isTerminal(BatchStatus status) {
        return status == BatchStatus.COMPLETED || status == BatchStatus.ROLLED_BACK;
    }

    private int countInflight(String batchId) {
        int inflight = 0;
        for (DeviceUpgrade upgrade : store.listUpgradesOfBatch(batchId)) {
            DeviceUpgradeStatus s = upgrade.getStatus();
            if (s == DeviceUpgradeStatus.PENDING
                    || s == DeviceUpgradeStatus.DOWNLOADING
                    || s == DeviceUpgradeStatus.INSTALLING) {
                inflight++;
            }
        }
        return inflight;
    }
}
