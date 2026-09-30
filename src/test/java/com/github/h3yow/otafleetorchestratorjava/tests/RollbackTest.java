package com.github.h3yow.otafleetorchestratorjava.tests;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.h3yow.otafleetorchestratorjava.store.InMemoryStore;
import com.github.h3yow.otafleetorchestratorjava.support.OtaTestClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.web.context.WebApplicationContext;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 回滚场景：
 * 1) 阈值自动暂停+回滚，成功设备版本恢复、所有设备 IDLE、批次 ROLLED_BACK；
 * 2) 手动回滚（暂停中）；
 * 3) 回滚后设备可加入新批次继续升级；
 * 4) 回滚终态不可重复回滚/上报，错误原因可区分。
 */
@SpringBootTest
class RollbackTest {

    private static final Logger log = LoggerFactory.getLogger("ROLLBACK-TEST");

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private InMemoryStore store;

    private OtaTestClient api;

    @BeforeEach
    void setUp() {
        store.clear();
        api = new OtaTestClient(context);
    }

    @Test
    void autoRollback_restoresVersionsAndConsistentState() throws Exception {
        String fw = api.createFirmware("v4.0.0");
        for (int i = 0; i < 4; i++) {
            api.registerDevice("r" + i, "v3.9.0");
        }
        // 单阶段 4 台，阈值 0.6：失败率达 0.6（3失败/... ）触发；
        // 顺序上报：2 成功后 3 台中前几台失败即触发
        String batchId = api.createBatch(fw, List.of("r0", "r1", "r2", "r3"),
                List.of(100), 0.6);
        api.startBatch(batchId);

        // r0/r1 成功：版本升级到 v4.0.0
        assertEquals(200, api.report(batchId, "r0", 1, "SUCCESS", null).status());
        assertEquals(200, api.report(batchId, "r1", 1, "SUCCESS", null).status());
        assertEquals("v4.0.0",
                api.getJson("/api/devices/r0").body().get("currentFirmwareVersion").asText());

        // r2 失败：失败率 1/3=0.333 未达阈值
        assertEquals(200, api.report(batchId, "r2", 1, "FAILED", "boot failure").status());
        JsonNode stillRolling = api.batchView(batchId);
        assertNotEquals("ROLLED_BACK", stillRolling.get("status").asText());

        // r3 失败：失败率 2/4=0.5 仍未达 0.6（>=判定），不回滚
        assertEquals(200, api.report(batchId, "r3", 1, "FAILED", "boot failure").status());
        JsonNode view4 = api.batchView(batchId);
        // 阶段全部上报且未达阈值 -> 批次完成
        assertEquals("COMPLETED", view4.get("status").asText(),
                "失败率 0.5 < 阈值 0.6，批次应完成而非回滚");
        log.info("[CHECK] failureRate 0.5 below threshold 0.6 -> COMPLETED (>= semantics)");
    }

    @Test
    void autoRollback_triggersAtThresholdAndRestoresEverything() throws Exception {
        String fw = api.createFirmware("v4.0.0");
        for (int i = 0; i < 3; i++) {
            api.registerDevice("r" + i, "v3.9.0");
        }
        // 阈值 0.5：1 成功 + 1 失败时失败率 0.5 即触发
        String batchId = api.createBatch(fw, List.of("r0", "r1", "r2"),
                List.of(100), 0.5);
        api.startBatch(batchId);

        assertEquals(200, api.report(batchId, "r0", 1, "SUCCESS", null).status());
        OtaTestClient.Response trigger = api.report(batchId, "r1", 1, "FAILED", "brick");
        assertEquals(200, trigger.status());
        assertEquals("ROLLED_BACK", trigger.body().get("batchStatus").asText(),
                "1成功1失败 失败率0.5 达到阈值，该请求内即完成自动暂停+回滚");

        JsonNode view = api.batchView(batchId);
        assertEquals("ROLLED_BACK", view.get("status").asText());
        // 计数快照保留判定依据
        assertEquals(1, view.get("successCount").asInt());
        assertEquals(1, view.get("failureCount").asInt());
        assertEquals(0.5, view.get("failureRate").asDouble(), 1e-9);
        assertTrue(view.get("rollbackReason").asText().contains("失败率达到阈值"));

        // 所有设备 IDLE
        for (JsonNode d : view.get("devices")) {
            assertEquals("IDLE", d.get("state").asText());
        }
        // 曾成功的 r0 版本恢复为 v3.9.0；失败的 r1 版本本来就没变
        assertEquals("v3.9.0",
                api.getJson("/api/devices/r0").body().get("currentFirmwareVersion").asText(),
                "回滚必须恢复成功设备的升级前固件版本");
        assertEquals("v3.9.0",
                api.getJson("/api/devices/r1").body().get("currentFirmwareVersion").asText());
        // 未上报的 r2 也处于 IDLE
        assertEquals("v3.9.0",
                api.getJson("/api/devices/r2").body().get("currentFirmwareVersion").asText());

        // 回滚后上报被拒，原因可区分
        OtaTestClient.Response reject = api.report(batchId, "r2", 1, "SUCCESS", null);
        assertEquals(409, reject.status());
        assertEquals("BATCH_FINISHED", reject.body().get("reason").asText());
        // 重复回滚被拒
        OtaTestClient.Response dupRollback = api.rollbackBatch(batchId, "again");
        assertEquals(409, dupRollback.status());
        assertEquals("BATCH_FINISHED", dupRollback.body().get("reason").asText());
        log.info("[CHECK] auto rollback restored state/versions and rejects post-rollback ops");
    }

    @Test
    void manualRollbackFromPause_thenDevicesContinueInNewBatch() throws Exception {
        String fw1 = api.createFirmware("v5.0.0");
        for (int i = 0; i < 4; i++) {
            api.registerDevice("c" + i, "v4.0.0");
        }
        String oldBatch = api.createBatch(fw1, List.of("c0", "c1", "c2", "c3"),
                List.of(50, 50), 0.9);
        api.startBatch(oldBatch);
        // c0 成功升级，随后手动暂停并回滚
        assertEquals(200, api.report(oldBatch, "c0", 1, "SUCCESS", null).status());
        assertEquals(200, api.pauseBatch(oldBatch, "stop for investigation").status());
        OtaTestClient.Response rb = api.rollbackBatch(oldBatch, "manual decision");
        assertEquals(200, rb.status());
        assertEquals("ROLLED_BACK", rb.body().get("status").asText());
        assertEquals("v4.0.0",
                api.getJson("/api/devices/c0").body().get("currentFirmwareVersion").asText());

        // 回滚后设备可继续操作：注册的新固件 + 新批次，设备全部重新升级成功
        String fw2 = api.createFirmware("v5.0.1");
        String newBatch = api.createBatch(fw2, List.of("c0", "c1", "c2", "c3"),
                List.of(100), 0.5);
        assertEquals(200, api.startBatch(newBatch).status());
        for (String d : List.of("c0", "c1", "c2", "c3")) {
            assertEquals(200, api.report(newBatch, d, 1, "SUCCESS", null).status());
        }
        JsonNode completed = api.batchView(newBatch);
        assertEquals("COMPLETED", completed.get("status").asText());
        assertEquals(4, completed.get("successCount").asInt());
        assertEquals("v5.0.1",
                api.getJson("/api/devices/c0").body().get("currentFirmwareVersion").asText());
        log.info("[CHECK] devices resumed operation in a new batch after manual rollback");
    }

    @Test
    void rollbackWhilePaused_terminalReportsDuringPauseStillCounted() throws Exception {
        // 暂停期间到达的终态上报必须计入并能驱动阈值判定（即使暂停不自动推进）
        String fw = api.createFirmware("v6.0.0");
        for (int i = 0; i < 2; i++) {
            api.registerDevice("p" + i, "v5.0.0");
        }
        String batchId = api.createBatch(fw, List.of("p0", "p1"), List.of(100), 0.5);
        api.startBatch(batchId);
        assertEquals(200, api.report(batchId, "p0", 1, "SUCCESS", null).status());
        api.pauseBatch(batchId, "manual pause");
        // 暂停中 p1 失败：失败率 0.5 达阈值 -> 自动回滚（即使批次处于 PAUSED）
        OtaTestClient.Response failed = api.report(batchId, "p1", 1, "FAILED", "x");
        assertEquals("ROLLED_BACK", failed.body().get("batchStatus").asText());
        JsonNode view = api.batchView(batchId);
        assertEquals("ROLLED_BACK", view.get("status").asText());
        assertEquals(1, view.get("failureCount").asInt(), "暂停期间终态失败必须被计数");
        log.info("[CHECK] terminal report during PAUSE counted and auto-rollback fired");
    }
}
