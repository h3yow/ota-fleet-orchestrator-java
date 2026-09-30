package com.github.h3yow.otafleetorchestratorjava.tests;

import com.github.h3yow.otafleetorchestratorjava.domain.enums.BatchStatus;
import com.github.h3yow.otafleetorchestratorjava.domain.enums.DeviceUpgradeStatus;
import com.github.h3yow.otafleetorchestratorjava.domain.enums.PauseReason;
import com.github.h3yow.otafleetorchestratorjava.dto.request.CreateBatchRequest;
import com.github.h3yow.otafleetorchestratorjava.dto.request.ProgressReportRequest;
import com.github.h3yow.otafleetorchestratorjava.dto.request.ReportResult;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 失败阈值：4 台一批，阈值 50%。失败率 50%（2/4）不暂停；再失败一台到 75%（3/4）自动暂停。
 * 暂停期间在途设备仍可上报；恢复后可继续推进。
 */
class FailureThresholdTest {

    private static final Logger log = LoggerFactory.getLogger(FailureThresholdTest.class);

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
        for (int i = 1; i <= 4; i++) {
            deviceService.register("dev-" + i, "v1.0.0");
        }
        bid = batchService.create(new CreateBatchRequest(fw.getId(), null, 4, 50)).getId();
        batchService.advance(bid);
    }

    private void reportFailure(String device, long seq, String req) {
        progressService.report(device,
                new ProgressReportRequest(bid, DeviceUpgradeStatus.FAILED, seq, req));
    }

    @Test
    void failureRateAboveThreshold_triggersAutoPause() {
        // 2 失败 / 4 下发 = 50%，未严格大于阈值，不暂停
        reportFailure("dev-1", 1L, "f1");
        reportFailure("dev-2", 1L, "f2");
        assertThat(batchService.get(bid).getStatus()).isEqualTo(BatchStatus.ACTIVE);

        // 3 失败 = 75% > 50%，自动暂停
        ReportResult r3 = progressService.report("dev-3",
                new ProgressReportRequest(bid, DeviceUpgradeStatus.FAILED, 1L, "f3"));
        UpgradeBatch batch = batchService.get(bid);
        assertThat(r3.triggeredAutoPause()).isTrue();
        assertThat(batch.getStatus()).isEqualTo(BatchStatus.PAUSED);
        assertThat(batch.getPauseReason()).isEqualTo(PauseReason.FAILURE_THRESHOLD);
        assertThat(batch.getFailedCount()).isEqualTo(3);
        log.info("[threshold] 失败率 75%>50% 自动暂停，判定依据 failed/dispatched=3/4");

        // 暂停期间在途设备仍可上报（dev-4 成功），但不会因为新成功而改变已暂停状态
        ReportResult r4 = progressService.report("dev-4",
                new ProgressReportRequest(bid, DeviceUpgradeStatus.DOWNLOADING, 1L, "s4d"));
        progressService.report("dev-4",
                new ProgressReportRequest(bid, DeviceUpgradeStatus.INSTALLING, 2L, "s4i"));
        ReportResult r4s = progressService.report("dev-4",
                new ProgressReportRequest(bid, DeviceUpgradeStatus.SUCCESS, 3L, "s4s"));
        assertThat(r4s.batchStatus()).isEqualTo(BatchStatus.PAUSED);
        assertThat(batchService.get(bid).getSuccessCount()).isEqualTo(1);

        // 恢复后批次可继续（这里已全部终态，推进即完成）
        batchService.resume(bid);
        assertThat(batchService.get(bid).getStatus()).isEqualTo(BatchStatus.ACTIVE);
        batchService.advance(bid);
        assertThat(batchService.get(bid).getStatus()).isEqualTo(BatchStatus.COMPLETED);
        log.info("[threshold] 恢复后推进，批次 COMPLETED，success=1/failed=3");
    }
}
