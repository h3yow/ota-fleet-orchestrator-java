package com.github.h3yow.otafleetorchestratorjava.tests;

import com.github.h3yow.otafleetorchestratorjava.domain.enums.DeviceUpgradeStatus;
import com.github.h3yow.otafleetorchestratorjava.dto.request.CreateBatchRequest;
import com.github.h3yow.otafleetorchestratorjava.dto.request.ProgressReportRequest;
import com.github.h3yow.otafleetorchestratorjava.dto.request.ReportResult;
import com.github.h3yow.otafleetorchestratorjava.exception.DomainException;
import com.github.h3yow.otafleetorchestratorjava.exception.ErrorCode;
import com.github.h3yow.otafleetorchestratorjava.model.Firmware;
import com.github.h3yow.otafleetorchestratorjava.service.DeviceService;
import com.github.h3yow.otafleetorchestratorjava.service.FirmwareService;
import com.github.h3yow.otafleetorchestratorjava.service.ProgressService;
import com.github.h3yow.otafleetorchestratorjava.service.UpgradeBatchService;
import com.github.h3yow.otafleetorchestratorjava.store.InMemoryStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 幂等与乱序：
 * - 同一 requestId 重复上报 -> 回放首次结果，不重复计数、不重复推进；
 * - seq 回退 -> STALE_SEQUENCE 拒绝；
 * - 非法状态跳转（如 PENDING 直接 SUCCESS）-> ILLEGAL_STATUS_TRANSITION；
 * - 已成功后再报失败（状态倒退）-> ILLEGAL_STATUS_TRANSITION；
 * - 未下发设备上报 -> DEVICE_NOT_IN_BATCH。
 */
class IdempotencyAndOrderingTest {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyAndOrderingTest.class);

    private InMemoryStore store;
    private FirmwareService firmwareService;
    private DeviceService deviceService;
    private UpgradeBatchService batchService;
    private ProgressService progressService;
    private String bid;

    @BeforeEach
    void setUp() {
        store = new InMemoryStore();
        firmwareService = new FirmwareService(store);
        deviceService = new DeviceService(store);
        batchService = new UpgradeBatchService(store);
        progressService = new ProgressService(store);

        Firmware fw = firmwareService.create("v2.0.0");
        firmwareService.release(fw.getId());
        deviceService.register("dev-1", "v1.0.0");
        bid = batchService.create(new CreateBatchRequest(fw.getId(), null, 10, 50)).getId();
        batchService.advance(bid);
    }

    @Test
    void duplicateRequest_isIdempotentAndNotDoubleCounted() {
        ProgressReportRequest req = new ProgressReportRequest(
                bid, DeviceUpgradeStatus.DOWNLOADING, 1L, "req-1");
        ReportResult first = progressService.report("dev-1", req);
        assertThat(first.duplicate()).isFalse();
        assertThat(first.deviceStatus()).isEqualTo(DeviceUpgradeStatus.DOWNLOADING);

        // 同 requestId 重放 3 次
        for (int i = 0; i < 3; i++) {
            ReportResult replay = progressService.report("dev-1", req);
            assertThat(replay.duplicate()).isTrue();
            assertThat(replay.deviceStatus()).isEqualTo(DeviceUpgradeStatus.DOWNLOADING);
        }
        // 仍停留在 DOWNLOADING，lastSeq 不被重复改写
        assertThat(store.findUpgrade(bid, "dev-1").orElseThrow().getLastSeq()).isEqualTo(1L);
        log.info("[idempotent] 同一 requestId 重放 3 次，设备状态保持 DOWNLOADING，未重复计数");
    }

    @Test
    void staleSequence_isRejected() {
        progressService.report("dev-1",
                new ProgressReportRequest(bid, DeviceUpgradeStatus.DOWNLOADING, 10L, "r-10"));
        assertThatThrownBy(() -> progressService.report("dev-1",
                new ProgressReportRequest(bid, DeviceUpgradeStatus.INSTALLING, 5L, "r-5")))
                .isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).getCode())
                .isEqualTo(ErrorCode.STALE_SEQUENCE);
        log.info("[ordering] seq=5 < lastSeq=10 被拒绝，原因 STALE_SEQUENCE");
    }

    @Test
    void illegalTransition_pendingToSuccess_isRejected() {
        assertThatThrownBy(() -> progressService.report("dev-1",
                new ProgressReportRequest(bid, DeviceUpgradeStatus.SUCCESS, 1L, "r-jump")))
                .isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).getCode())
                .isEqualTo(ErrorCode.ILLEGAL_STATUS_TRANSITION);
        log.info("[ordering] PENDING->SUCCESS 非法跳转被拒绝，原因 ILLEGAL_STATUS_TRANSITION");
    }

    @Test
    void successThenFailure_cannotRollbackState() {
        progressService.report("dev-1",
                new ProgressReportRequest(bid, DeviceUpgradeStatus.DOWNLOADING, 1L, "a"));
        progressService.report("dev-1",
                new ProgressReportRequest(bid, DeviceUpgradeStatus.INSTALLING, 2L, "b"));
        progressService.report("dev-1",
                new ProgressReportRequest(bid, DeviceUpgradeStatus.SUCCESS, 3L, "c"));
        assertThatThrownBy(() -> progressService.report("dev-1",
                new ProgressReportRequest(bid, DeviceUpgradeStatus.FAILED, 4L, "d")))
                .isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).getCode())
                .isEqualTo(ErrorCode.ILLEGAL_STATUS_TRANSITION);
        // 成功计数仍为 1，没有被失败倒退
        assertThat(store.findBatch(bid).orElseThrow().getSuccessCount()).isEqualTo(1);
        assertThat(store.findBatch(bid).orElseThrow().getFailedCount()).isZero();
        log.info("[ordering] SUCCESS 后报 FAILED 被拒绝，成功计数未被回退");
    }

    @Test
    void unassignedDevice_report_isRejected() {
        deviceService.register("dev-x", "v1.0.0");
        assertThatThrownBy(() -> progressService.report("dev-x",
                new ProgressReportRequest(bid, DeviceUpgradeStatus.DOWNLOADING, 1L, "rx")))
                .isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).getCode())
                .isEqualTo(ErrorCode.DEVICE_NOT_IN_BATCH);
    }
}
