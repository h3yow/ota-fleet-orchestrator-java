package com.github.h3yow.otafleetorchestratorjava.tests;

import com.github.h3yow.otafleetorchestratorjava.domain.enums.BatchStatus;
import com.github.h3yow.otafleetorchestratorjava.domain.enums.DeviceUpgradeStatus;
import com.github.h3yow.otafleetorchestratorjava.dto.request.CreateBatchRequest;
import com.github.h3yow.otafleetorchestratorjava.dto.request.ProgressReportRequest;
import com.github.h3yow.otafleetorchestratorjava.model.Firmware;
import com.github.h3yow.otafleetorchestratorjava.model.UpgradeBatch;
import com.github.h3yow.otafleetorchestratorjava.service.DeviceService;
import com.github.h3yow.otafleetorchestratorjava.service.FirmwareService;
import com.github.h3yow.otafleetorchestratorjava.service.ProgressService;
import com.github.h3yow.otafleetorchestratorjava.service.UpgradeBatchService;
import com.github.h3yow.otafleetorchestratorjava.store.InMemoryStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 并发上报：
 * 场景A：100 台设备、16 线程并发走完 DOWNLOADING->INSTALLING->SUCCESS，
 *        最终 success=100、failed=0、批次 COMPLETED。
 * 场景B：40 台设备，各自的 SUCCESS 请求（同一 requestId）被 5 个线程同时提交，
 *        最终计数不翻倍，重复请求均命中幂等。
 * 场景C：60 台设备并发失败（阈值 30%），自动暂停恰好触发一次，failed 计数不丢/不重。
 */
class ConcurrentReportTest {

    private static final Logger log = LoggerFactory.getLogger(ConcurrentReportTest.class);

    private InMemoryStore store;
    private FirmwareService firmwareService;
    private DeviceService deviceService;
    private UpgradeBatchService batchService;
    private ProgressService progressService;

    @BeforeEach
    void setUp() {
        store = new InMemoryStore();
        firmwareService = new FirmwareService(store);
        deviceService = new DeviceService(store);
        batchService = new UpgradeBatchService(store);
        progressService = new ProgressService(store);
    }

    private String setupBatch(int deviceCount, int threshold) {
        Firmware fw = firmwareService.create("v2.0.0");
        firmwareService.release(fw.getId());
        for (int i = 0; i < deviceCount; i++) {
            deviceService.register("dev-" + i, "v1.0.0");
        }
        String bid = batchService.create(
                new CreateBatchRequest(fw.getId(), null, deviceCount, threshold)).getId();
        batchService.advance(bid);
        return bid;
    }

    /** 单台设备完整上报成功序列。 */
    private void reportFullSuccess(String device, String bid, String idempotentSuffix, long baseSeq) {
        progressService.report(device, new ProgressReportRequest(
                bid, DeviceUpgradeStatus.DOWNLOADING, baseSeq, "d-" + device + idempotentSuffix));
        progressService.report(device, new ProgressReportRequest(
                bid, DeviceUpgradeStatus.INSTALLING, baseSeq + 1, "i-" + device + idempotentSuffix));
        progressService.report(device, new ProgressReportRequest(
                bid, DeviceUpgradeStatus.SUCCESS, baseSeq + 2, "s-" + device + idempotentSuffix));
    }

    @Test
    void concurrentSuccess_allCountedExactlyOnce_andCompletes() throws Exception {
        int n = 100;
        String bid = setupBatch(n, 50);

        ExecutorService pool = Executors.newFixedThreadPool(16);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(n);
        for (int i = 0; i < n; i++) {
            String device = "dev-" + i;
            pool.submit(() -> {
                try {
                    start.await();
                    reportFullSuccess(device, bid, "", 1L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        pool.shutdown();

        UpgradeBatch batch = batchService.get(bid);
        assertThat(batch.getSuccessCount()).isEqualTo(n);
        assertThat(batch.getFailedCount()).isZero();
        batchService.advance(bid);
        assertThat(batchService.get(bid).getStatus()).isEqualTo(BatchStatus.COMPLETED);
        log.info("[concurrent-success] {} 台设备并发上报成功，success={}，批次 COMPLETED", n, n);
    }

    @Test
    void concurrentDuplicateRequests_countedOnce() throws Exception {
        int n = 40;
        int replicas = 5;
        String bid = setupBatch(n, 50);

        ExecutorService pool = Executors.newFixedThreadPool(20);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(n * replicas);
        AtomicInteger duplicates = new AtomicInteger();
        List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < n; i++) {
            String device = "dev-" + i;
            // 每台设备先发 DOWNLOADING/INSTALLING（非终态），再并发重复提交同一个 SUCCESS
            progressService.report(device, new ProgressReportRequest(
                    bid, DeviceUpgradeStatus.DOWNLOADING, 1L, "d-" + device));
            progressService.report(device, new ProgressReportRequest(
                    bid, DeviceUpgradeStatus.INSTALLING, 2L, "i-" + device));
            for (int r = 0; r < replicas; r++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        var result = progressService.report(device,
                                new ProgressReportRequest(bid, DeviceUpgradeStatus.SUCCESS, 3L,
                                        "s-" + device));
                        if (result.duplicate()) {
                            duplicates.incrementAndGet();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } catch (Throwable t) {
                        errors.add(t);
                    } finally {
                        done.countDown();
                    }
                });
            }
        }
        start.countDown();
        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        pool.shutdown();

        assertThat(errors).isEmpty();
        UpgradeBatch batch = batchService.get(bid);
        assertThat(batch.getSuccessCount()).isEqualTo(n);
        assertThat(duplicates.get()).isEqualTo(n * (replicas - 1));
        log.info("[concurrent-duplicate] {} 台设备的 SUCCESS 各并发重复 {} 次，成功计数仍为 {}，幂等命中 {} 次",
                n, replicas, n, duplicates.get());
    }

    @Test
    void concurrentFailures_autoPauseFiresExactlyOnce() throws Exception {
        int n = 60;
        String bid = setupBatch(n, 30);

        ExecutorService pool = Executors.newFixedThreadPool(20);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(n);
        AtomicInteger autoPauseTriggers = new AtomicInteger();

        for (int i = 0; i < n; i++) {
            String device = "dev-" + i;
            pool.submit(() -> {
                try {
                    start.await();
                    var result = progressService.report(device,
                            new ProgressReportRequest(bid, DeviceUpgradeStatus.FAILED, 1L,
                                    "fail-" + device));
                    if (result.triggeredAutoPause()) {
                        autoPauseTriggers.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        pool.shutdown();

        UpgradeBatch batch = batchService.get(bid);
        assertThat(batch.getFailedCount()).isEqualTo(n);
        assertThat(batch.getStatus()).isEqualTo(BatchStatus.PAUSED);
        assertThat(autoPauseTriggers.get()).isEqualTo(1);
        log.info("[concurrent-failure] {} 台并发失败，failed={}（无丢更新），自动暂停仅触发 {} 次",
                n, batch.getFailedCount(), autoPauseTriggers.get());
    }
}
