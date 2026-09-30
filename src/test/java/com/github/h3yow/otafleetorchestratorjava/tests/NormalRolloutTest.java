package com.github.h3yow.otafleetorchestratorjava.tests;

import com.github.h3yow.otafleetorchestratorjava.domain.enums.BatchStatus;
import com.github.h3yow.otafleetorchestratorjava.domain.enums.DeviceUpgradeStatus;
import com.github.h3yow.otafleetorchestratorjava.dto.request.CreateBatchRequest;
import com.github.h3yow.otafleetorchestratorjava.dto.request.ProgressReportRequest;
import com.github.h3yow.otafleetorchestratorjava.dto.request.ReportResult;
import com.github.h3yow.otafleetorchestratorjava.exception.DomainException;
import com.github.h3yow.otafleetorchestratorjava.exception.ErrorCode;
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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 正常发布流程：5 台设备、每批 2 台，分三批推进直至 COMPLETED，全部成功。
 * 同时验证：未发布固件不能建批次、批次间在途门禁。
 */
class NormalRolloutTest {

    private static final Logger log = LoggerFactory.getLogger(NormalRolloutTest.class);

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

    @Test
    void fullHappyPath_stageByStage_thenCompleted() {
        Firmware fw = firmwareService.create("v2.0.0");

        // 未发布不能建批次
        assertThatThrownBy(() -> batchService.create(
                new CreateBatchRequest(fw.getId(), null, 2, 50)))
                .isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).getCode())
                .isEqualTo(ErrorCode.FIRMWARE_NOT_RELEASED);
        firmwareService.release(fw.getId());

        for (int i = 1; i <= 5; i++) {
            deviceService.register("dev-" + i, "v1.0.0");
            deviceService.heartbeat("dev-" + i);
        }

        UpgradeBatch batch = batchService.create(
                new CreateBatchRequest(fw.getId(), null, 2, 50));
        String bid = batch.getId();
        assertThat(batch.getStatus()).isEqualTo(BatchStatus.PENDING);

        // 分三批：2 / 2 / 1
        batchService.advance(bid);
        assertThat(batch.getCurrentStage()).isZero();
        // 第一批有在途设备，不能直接推第二批
        assertThatThrownBy(() -> batchService.advance(bid))
                .isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).getCode())
                .isEqualTo(ErrorCode.BATCH_HAS_INFLIGHT_DEVICES);

        reportSuccess("dev-1", bid, 0);
        reportSuccess("dev-2", bid, 0);
        batchService.advance(bid);
        assertThat(batch.getCurrentStage()).isEqualTo(1);
        reportSuccess("dev-3", bid, 1);
        reportSuccess("dev-4", bid, 1);
        batchService.advance(bid);
        assertThat(batch.getCurrentStage()).isEqualTo(2);
        reportSuccess("dev-5", bid, 2);

        // 再推进：全部下发且全部终态 -> COMPLETED
        batchService.advance(bid);
        UpgradeBatch done = batchService.get(bid);
        assertThat(done.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(done.getSuccessCount()).isEqualTo(5);
        assertThat(done.getFailedCount()).isZero();
        assertThat(done.getCompletedAt()).isNotNull();

        // 设备版本已更新，targetBatchId 已清空
        for (int i = 1; i <= 5; i++) {
            assertThat(deviceService.get("dev-" + i).getCurrentFirmwareVersion()).isEqualTo("v2.0.0");
            assertThat(deviceService.get("dev-" + i).getTargetBatchId()).isNull();
        }
        log.info("[normal-rollout] 全部 5 台设备升级成功，批次 COMPLETED");
    }

    private void reportSuccess(String deviceId, String bid, int stage) {
        long seq = 0;
        ReportResult r1 = progressService.report(deviceId,
                new ProgressReportRequest(bid, DeviceUpgradeStatus.DOWNLOADING, seq++, "r-" + deviceId + "-d"));
        ReportResult r2 = progressService.report(deviceId,
                new ProgressReportRequest(bid, DeviceUpgradeStatus.INSTALLING, seq++, "r-" + deviceId + "-i"));
        ReportResult r3 = progressService.report(deviceId,
                new ProgressReportRequest(bid, DeviceUpgradeStatus.SUCCESS, seq++, "r-" + deviceId + "-s"));
        log.info("[normal-rollout] device={}, stage={}, final={}, batch={}",
                deviceId, stage, r3.deviceStatus(), r3.batchStatus());
        assertThat(List.of(r1.deviceStatus(), r2.deviceStatus(), r3.deviceStatus()))
                .containsExactly(DeviceUpgradeStatus.DOWNLOADING,
                        DeviceUpgradeStatus.INSTALLING, DeviceUpgradeStatus.SUCCESS);
    }
}
