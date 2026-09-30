package com.github.h3yow.otafleetorchestratorjava.store;

import com.github.h3yow.otafleetorchestratorjava.domain.Batch;
import com.github.h3yow.otafleetorchestratorjava.domain.Device;
import com.github.h3yow.otafleetorchestratorjava.domain.Firmware;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * 内存存储：所有状态变更在同一把公平锁内完成，保证并发上报下的
 * "读判定-写状态-再判定推进"整体原子，杜绝重复推进、漏判与状态丢更新。
 *
 * <p>仅用于本地运行与测试；重启后数据丢失。
 */
@Component
public class InMemoryStore {

    private final Map<String, Firmware> firmwares = new ConcurrentHashMap<>();
    private final Map<String, Device> devices = new ConcurrentHashMap<>();
    private final Map<String, Batch> batches = new ConcurrentHashMap<>();

    private final ReentrantLock lock = new ReentrantLock(true);

    private final AtomicLong firmwareSeq = new AtomicLong();
    private final AtomicLong deviceSeq = new AtomicLong();
    private final AtomicLong batchSeq = new AtomicLong();

    public Map<String, Firmware> firmwares() {
        return firmwares;
    }

    public Map<String, Device> devices() {
        return devices;
    }

    public Map<String, Batch> batches() {
        return batches;
    }

    /** 在全局锁内执行有返回值的读/写操作。 */
    public <T> T withLock(Supplier<T> action) {
        lock.lock();
        try {
            return action.get();
        } finally {
            lock.unlock();
        }
    }

    /** 在全局锁内执行无返回值的写操作。 */
    public void withLock(Runnable action) {
        lock.lock();
        try {
            action.run();
        } finally {
            lock.unlock();
        }
    }

    public String nextFirmwareId() {
        return "fw-" + firmwareSeq.incrementAndGet();
    }

    public String nextDeviceId() {
        return "dev-" + deviceSeq.incrementAndGet();
    }

    public String nextBatchId() {
        return "batch-" + batchSeq.incrementAndGet();
    }

    /** 测试辅助：清空全部数据。 */
    public void clear() {
        withLock(() -> {
            firmwares.clear();
            devices.clear();
            batches.clear();
            firmwareSeq.set(0);
            deviceSeq.set(0);
            batchSeq.set(0);
        });
    }
}
