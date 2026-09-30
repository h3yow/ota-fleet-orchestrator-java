package com.github.h3yow.otafleetorchestratorjava.tests;

import com.github.h3yow.otafleetorchestratorjava.domain.enums.BatchStatus;
import com.github.h3yow.otafleetorchestratorjava.domain.enums.DeviceUpgradeStatus;
import com.github.h3yow.otafleetorchestratorjava.dto.request.CreateBatchRequest;
import com.github.h3yow.otafleetorchestratorjava.dto.request.ProgressReportRequest;
import com.github.h3yow.otafleetorchestratorjava.exception.DomainException;
import com.github.h3yow.otafleetorchestratorjava.exception.ErrorCode;
import com.github.h3yow.otafleetorchestratorjava.model.Device;
import com.github.h3yow.otafleetorchestratorjava.model.DeviceUpgrade;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 回滚场景：4 台设备（每批 4），dev-1/dev-2 成功（版本已到 v2），dev-3 失败，dev-4 仍在途。
 * 回滚要求：所有已下发设备版本恢复到 fromVersion、升级记录 ROLLED_BACK、targetBatchId 清空、
 * 批次 ROLLED_BACK 终态；回滚后上报被拒、重复回滚被拒；设备可重新建批次升级。
 */
class RollbackTest {

    private static final Logger log = LoggerFactory.getLogger(RollbackTest.class);

    private InMemoryStore store;
    private FirmwareService firmwareService;
    private DeviceService deviceService;
    private UpgradeBatchService batchService;
    private ProgressService progressService;
    private String bid;
    private Firmware fw;

    @BeforeEach
    void setUp() {
        store = new InMemoryStore();
        firmwareService = new FirmwareService(store);
        deviceService = new DeviceService(store);
        batchService = new UpgradeBatchService(store);
        progressService = new ProgressService(store);

        fw = firmwareService.create("v2.0.0");
        firmwareService.release(fw.getId());
        for (int i = 1; i <= 4; i++) {
            deviceService.register("dev-" + i, "v1.0.0");
        }
        bid = batchService.create(new CreateBatchRequest(fw.getId(), null, 4, 90)).getId();
        batchService.advance(bid);
    }

    @Test
    void rollback_restoresConsistentState_andDevicesReusable() {
        // dev-1/dev-2 成功；dev-3 失败；dev-4 在 INSTALLING（在途）
        success("dev-1");
        success("dev-2");
        progressService.report("dev-3",
                new ProgressReportRequest(bid, DeviceUpgradeStatus.FAILED, 1L, "f3"));
        progressService.report("dev-4",
                new ProgressReportRequest(bid, DeviceUpgradeStatus.DOWNLOADING, 1L, "d4"));
        progressService.report("dev-4",
                new ProgressReportRequest(bid, DeviceUpgradeStatus.INSTALLING, 2L, "i4"));

        assertThat(deviceService.get("dev-1").getCurrentFirmwareVersion()).isEqualTo("v2.0.0");

        // 回滚
        UpgradeBatch rolled = batchService.rollback(bid, "failure-rate-too-high");
        assertThat(rolled.getStatus()).isEqualTo(BatchStatus.ROLLED_BACK);
        assertThat(rolled.getRolledBackAt()).isNotNull();

        for (int i = 1; i <= 4; i++) {
            Device d = deviceService.get("dev-" + i);
            DeviceUpgrade u = store.findUpgrade(bid, "dev-" + i).orElseThrow();
            assertThat(d.getCurrentFirmwareVersion()).isEqualTo("v1.0.0");
            assertThat(d.getTargetBatchId()).isNull();
            assertThat(u.getStatus()).isEqualTo(DeviceUpgradeStatus.ROLLED_BACK);
        }
        log.info("[rollback] 4 台设备版本统一恢复 v1.0.0，升级记录 ROLLED_BACK，批次终态 ROLLED_BACK");

        // 回滚后上报被拒
        assertThatThrownBy(() -> progressService.report("dev-4",
                new ProgressReportRequest(bid, DeviceUpgradeStatus.SUCCESS, 3L, "late-s4")))
                .isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).getCode())
                .satisfiesAnyOf(c -> assertThat(c).isEqualTo(ErrorCode.REPORT_REJECTED_TERMINAL));
        // 重复回滚被拒
        assertThatThrownBy(() -> batchService.rollback(bid, "again"))
                .isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).getCode())
                .isEqualTo(ErrorCode.BATCH_ALREADY_TERMINAL);
        // 终态不能推进/恢复
        assertThatThrownBy(() -> batchService.advance(bid))
                .isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).getCode())
                .isEqualTo(ErrorCode.BATCH_ALREADY_TERMINAL);
        assertThatThrownBy(() -> batchService.resume(bid))
                .isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).getCode())
                .isEqualTo(ErrorCode.BATCH_NOT_PAUSED);

        // 设备已恢复一致且可被新批次重新选中
        UpgradeBatch batch2 = batchService.create(new CreateBatchRequest(fw.getId(), null, 4, 90));
        assertThat(batch2.getEligibleDeviceIds()).containsExactly("dev-1", "dev-2", "dev-3", "dev-4");
        batchService.advance(batch2.getId());
        assertThat(store.findUpgrade(batch2.getId(), "dev-1").orElseThrow().getStatus())
                .isEqualTo(DeviceUpgradeStatus.PENDING);
        log.info("[rollback] 回滚后可基于相同设备创建新批次并重新下发，状态可继续操作");
    }

    private void success(String device) {
        progressService.report(device,
                new ProgressReportRequest(bid, DeviceUpgradeStatus.DOWNLOADING, 1L, device + "d"));
        progressService.report(device,
                new ProgressReportRequest(bid, DeviceUpgradeStatus.INSTALLING, 2L, device + "i"));
        progressService.report(device,
                new ProgressReportRequest(bid, DeviceUpgradeStatus.SUCCESS, 3L, device + "s"));
    }
}
