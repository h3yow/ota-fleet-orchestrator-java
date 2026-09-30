package com.github.h3yow.otafleetorchestratorjava.store;

import com.github.h3yow.otafleetorchestratorjava.model.Device;
import com.github.h3yow.otafleetorchestratorjava.model.DeviceUpgrade;
import com.github.h3yow.otafleetorchestratorjava.model.Firmware;
import com.github.h3yow.otafleetorchestratorjava.model.IdempotentReportRecord;
import com.github.h3yow.otafleetorchestratorjava.model.UpgradeBatch;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * 内存存储 + 并发控制。
 * <p>
 * 锁规则：
 * - 每个批次一把锁（{@link #batchLock(String)}），同一批次的创建下发、推进、暂停/恢复、回滚、
 *   进度上报判定都在该锁内完成，保证读-改-写原子，杜绝重复推进、漏判和计数丢更新。
 * - 每台设备一把锁（{@link #deviceLock(String)}），保护注册 / 心跳以及设备字段。
 * - 不同批次之间互不阻塞。
 * 所有 Map 均为 ConcurrentHashMap，读侧无锁。
 */
@Component
public class InMemoryStore {

    private final Map<String, Firmware> firmwares = new ConcurrentHashMap<>();
    private final Map<String, Device> devices = new ConcurrentHashMap<>();
    private final Map<String, UpgradeBatch> batches = new ConcurrentHashMap<>();
    /** key: batchId + ":" + deviceId */
    private final Map<String, DeviceUpgrade> upgrades = new ConcurrentHashMap<>();
    /** key: deviceId + ":" + requestId */
    private final Map<String, IdempotentReportRecord> processedReports = new ConcurrentHashMap<>();

    private final Map<String, ReentrantLock> batchLocks = new ConcurrentHashMap<>();
    private final Map<String, ReentrantLock> deviceLocks = new ConcurrentHashMap<>();

    // ---------------- 锁 ----------------

    public ReentrantLock batchLock(String batchId) {
        return batchLocks.computeIfAbsent(batchId, k -> new ReentrantLock());
    }

    public ReentrantLock deviceLock(String deviceId) {
        return deviceLocks.computeIfAbsent(deviceId, k -> new ReentrantLock());
    }

    public <T> T withBatchLock(String batchId, Supplier<T> action) {
        ReentrantLock lock = batchLock(batchId);
        lock.lock();
        try {
            return action.get();
        } finally {
            lock.unlock();
        }
    }

    public void withBatchLock(String batchId, Runnable action) {
        withBatchLock(batchId, () -> {
            action.run();
            return null;
        });
    }

    public <T> T withDeviceLock(String deviceId, Supplier<T> action) {
        ReentrantLock lock = deviceLock(deviceId);
        lock.lock();
        try {
            return action.get();
        } finally {
            lock.unlock();
        }
    }

    public void withDeviceLock(String deviceId, Runnable action) {
        withDeviceLock(deviceId, () -> {
            action.run();
            return null;
        });
    }

    // ---------------- 固件 ----------------

    public Optional<Firmware> findFirmware(String id) {
        return Optional.ofNullable(firmwares.get(id));
    }

    public Optional<Firmware> findFirmwareByVersion(String version) {
        return firmwares.values().stream().filter(f -> f.getVersion().equals(version)).findFirst();
    }

    public void putFirmware(Firmware firmware) {
        firmwares.put(firmware.getId(), firmware);
    }

    public List<Firmware> listFirmwares() {
        return new ArrayList<>(firmwares.values());
    }

    // ---------------- 设备 ----------------

    public Optional<Device> findDevice(String id) {
        return Optional.ofNullable(devices.get(id));
    }

    public void putDevice(Device device) {
        devices.put(device.getId(), device);
    }

    public List<Device> listDevices() {
        return new ArrayList<>(devices.values());
    }

    // ---------------- 批次 ----------------

    public Optional<UpgradeBatch> findBatch(String id) {
        return Optional.ofNullable(batches.get(id));
    }

    public void putBatch(UpgradeBatch batch) {
        batches.put(batch.getId(), batch);
    }

    public List<UpgradeBatch> listBatches() {
        return new ArrayList<>(batches.values());
    }

    // ---------------- 设备升级记录 ----------------

    private static String upgradeKey(String batchId, String deviceId) {
        return batchId + ":" + deviceId;
    }

    public Optional<DeviceUpgrade> findUpgrade(String batchId, String deviceId) {
        return Optional.ofNullable(upgrades.get(upgradeKey(batchId, deviceId)));
    }

    public void putUpgrade(DeviceUpgrade upgrade) {
        upgrades.put(upgradeKey(upgrade.getBatchId(), upgrade.getDeviceId()), upgrade);
    }

    /** 返回该批次全部设备升级记录（快照）。 */
    public List<DeviceUpgrade> listUpgradesOfBatch(String batchId) {
        List<DeviceUpgrade> result = new ArrayList<>();
        for (DeviceUpgrade upgrade : upgrades.values()) {
            if (upgrade.getBatchId().equals(batchId)) {
                result.add(upgrade);
            }
        }
        return result;
    }

    // ---------------- 上报幂等 ----------------

    private static String reportKey(String deviceId, String requestId) {
        return deviceId + ":" + requestId;
    }

    public Optional<IdempotentReportRecord> findProcessedReport(String deviceId, String requestId) {
        if (requestId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(processedReports.get(reportKey(deviceId, requestId)));
    }

    public void saveProcessedReport(String deviceId, String requestId, IdempotentReportRecord record) {
        if (requestId != null) {
            processedReports.put(reportKey(deviceId, requestId), record);
        }
    }
}
