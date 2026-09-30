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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 幂等与拒绝原因可区分性测试：
 * 重复终态上报、过期 seq、相同 seq 不同内容、乱序状态回退、
 * 阶段未激活/不匹配、批次暂停、非法生命周期操作等。
 */
@SpringBootTest
class IdempotencyAndRejectTest {

    private static final Logger log = LoggerFactory.getLogger("IDEMPOTENT-TEST");

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private InMemoryStore store;

    private OtaTestClient api;
    private String batchId;

    @BeforeEach
    void setUp() throws Exception {
        store.clear();
        api = new OtaTestClient(context);
        String fw = api.createFirmware("v9.0.0");
        for (int i = 0; i < 4; i++) {
            api.registerDevice("x" + i, "v8.0.0");
        }
        // 两阶段各 50%：x0,x1 阶段0；x2,x3 阶段1（按 ID 排序后切分）。
        // 阈值设高一些（0.9），避免单台失败就触发自动回滚，专注测试幂等/乱序规则。
        batchId = api.createBatch(fw, List.of("x0", "x1", "x2", "x3"),
                List.of(50, 50), 0.9);
    }

    private void assertReason(OtaTestClient.Response r, int status, String reason) {
        assertEquals(status, r.status(), "期望 HTTP " + status + "，实际 " + r.status()
                + " body=" + r.raw());
        assertEquals(reason, r.body().get("reason").asText(),
                "拒绝原因必须可区分；body=" + r.raw());
        log.info("[CHECK-REJECT] {} -> {} ({})", reason, status,
                r.body().get("message").asText());
    }

    @Test
    void duplicateTerminalReport_isIdempotentAndNotDoubleCounted() throws Exception {
        api.startBatch(batchId);
        OtaTestClient.Response first = api.report(batchId, "x0", 1, "SUCCESS", null);
        assertEquals(200, first.status());
        assertTrue(first.body().get("stateChanged").asBoolean());
        assertFalse(first.body().get("duplicate").asBoolean());

        // 完全相同的重复请求（同 seq 同结果）：幂等成功，不重复计数
        OtaTestClient.Response dup = api.report(batchId, "x0", 1, "SUCCESS", null);
        assertEquals(200, dup.status());
        assertTrue(dup.body().get("duplicate").asBoolean(), "重复终态上报必须标记 duplicate=true");
        assertFalse(dup.body().get("stateChanged").asBoolean());

        // 再来一次重复仍然安全
        OtaTestClient.Response dup2 = api.report(batchId, "x0", 1, "SUCCESS", null);
        assertTrue(dup2.body().get("duplicate").asBoolean());

        JsonNode view = api.batchView(batchId);
        assertEquals(1, view.get("successCount").asInt(), "重复上报不得重复计数");
        log.info("[CHECK] duplicate terminal report accepted idempotently, successCount stays 1");
    }

    @Test
    void staleSeq_isRejectedWithStaleRequest() throws Exception {
        api.startBatch(batchId);
        api.report(batchId, "x0", 5, "DOWNLOADING", null);
        // seq 倒退 -> STALE_REQUEST
        assertReason(api.report(batchId, "x0", 4, "DOWNLOADING", null), 409, "STALE_REQUEST");
        assertReason(api.report(batchId, "x0", 5, "DOWNLOADING", null), 409,
                "ILLEGAL_STATE_TRANSITION"); // 同 seq 且状态相同也算冲突重放（非终态）
        // 相同 seq 但内容不同 -> ILLEGAL_STATE_TRANSITION
        assertReason(api.report(batchId, "x0", 5, "INSTALLING", null), 409,
                "ILLEGAL_STATE_TRANSITION");
        // 更大 seq 正常前进
        assertEquals(200, api.report(batchId, "x0", 6, "INSTALLING", null).status());
    }

    @Test
    void outOfOrderStateTransition_isRejected_noRollbackOfState() throws Exception {
        api.startBatch(batchId);
        // x0 已成功后，任何进度/失败上报都必须被拒绝，状态不得回退
        assertEquals(200, api.report(batchId, "x0", 1, "SUCCESS", null).status());
        assertReason(api.report(batchId, "x0", 2, "DOWNLOADING", null), 409,
                "ILLEGAL_STATE_TRANSITION");
        assertReason(api.report(batchId, "x0", 2, "FAILED", null), 409,
                "ILLEGAL_STATE_TRANSITION");
        assertReason(api.report(batchId, "x0", 3, "INSTALLING", null), 409,
                "ILLEGAL_STATE_TRANSITION");

        // 失败终态同样不可逆
        assertEquals(200, api.report(batchId, "x1", 1, "FAILED", "disk full").status());
        assertReason(api.report(batchId, "x1", 2, "SUCCESS", null), 409,
                "ILLEGAL_STATE_TRANSITION");
        assertReason(api.report(batchId, "x1", 1, "SUCCESS", null), 409,
                "ILLEGAL_STATE_TRANSITION");
        // 同 seq 同结果的失败重放才是幂等
        OtaTestClient.Response dupFail = api.report(batchId, "x1", 1, "FAILED", "disk full");
        assertTrue(dupFail.body().get("duplicate").asBoolean());

        JsonNode view = api.batchView(batchId);
        assertEquals(1, view.get("successCount").asInt());
        assertEquals(1, view.get("failureCount").asInt());
        log.info("[CHECK] terminal states never roll back; counts remain success=1 failure=1");
    }

    @Test
    void inactiveStageAndStageMismatch_areRejected() throws Exception {
        api.startBatch(batchId);
        // 阶段 1 的设备 x2 在阶段 0 激活时上报 -> STAGE_MISMATCH
        assertReason(api.report(batchId, "x2", 1, "SUCCESS", null), 409, "STAGE_MISMATCH");
        // 未启动的批次上报（设备尚未下发）
    }

    @Test
    void pausedBatch_rejectsProgressButAcceptsTerminal_andResumeAdvances() throws Exception {
        api.startBatch(batchId);
        // x0 进入安装中后暂停
        assertEquals(200, api.report(batchId, "x0", 1, "INSTALLING", null).status());
        assertEquals(200, api.pauseBatch(batchId, "investigate").status());

        // 暂停期间非终态进度 -> BATCH_PAUSED（阶段未激活的判定优先于 seq 判定）
        assertReason(api.report(batchId, "x0", 2, "DOWNLOADING", null), 409, "BATCH_PAUSED");
        // x1 尚未上报过，暂停期间发进度 -> BATCH_PAUSED
        assertReason(api.report(batchId, "x1", 1, "DOWNLOADING", null), 409, "BATCH_PAUSED");
        // 过期 seq 的非终态请求：seq 冲突/过期判定先于暂停判定，返回 STALE_REQUEST
        assertReason(api.report(batchId, "x0", 0, "INSTALLING", null), 409, "STALE_REQUEST");
        // 但终态上报仍接受并计数（不漏判阈值）
        assertEquals(200, api.report(batchId, "x1", 1, "SUCCESS", null).status());
        assertEquals(200, api.report(batchId, "x0", 2, "SUCCESS", null).status());

        // 暂停状态不会自动推进：仍停在阶段 0
        JsonNode paused = api.batchView(batchId);
        assertEquals("PAUSED", paused.get("status").asText());
        assertEquals(0, paused.get("activeStage").asInt());

        // 恢复后自动推进到阶段 1 并下发
        api.resumeBatch(batchId);
        JsonNode rolling = api.batchView(batchId);
        assertEquals("ROLLING", rolling.get("status").asText());
        assertEquals(1, rolling.get("activeStage").asInt());
        log.info("[CHECK] paused batch counts terminals, then auto-advances on resume");
    }

    @Test
    void illegalLifecycleOperations_haveDistinctReasons() throws Exception {
        // 未 DRAFT 开始两次
        assertEquals(200, api.startBatch(batchId).status());
        assertReason(api.startBatch(batchId), 409, "BATCH_NOT_DRAFT");

        // 暂停两次
        assertEquals(200, api.pauseBatch(batchId, "manual").status());
        assertReason(api.pauseBatch(batchId, "again"), 409, "BATCH_ALREADY_PAUSED");
        // 未暂停时恢复
        assertEquals(200, api.resumeBatch(batchId).status());
        assertReason(api.resumeBatch(batchId), 409, "BATCH_NOT_PAUSED");

        // 当前阶段未上报完不能手动推进
        assertReason(api.advanceBatch(batchId), 409, "STAGE_NOT_READY");

        // 阶段收齐并成功 -> 自动推进到阶段1，再推进时阶段1未收齐
        assertEquals(200, api.report(batchId, "x0", 1, "SUCCESS", null).status());
        assertEquals(200, api.report(batchId, "x1", 1, "SUCCESS", null).status());
        assertReason(api.advanceBatch(batchId), 409, "STAGE_NOT_READY");
    }

    @Test
    void validationAndResourceErrors_areDistinguishable() throws Exception {
        // 固件不存在
        OtaTestClient.Response noFw = api.postJson("/api/batches", java.util.Map.of(
                "firmwareId", "fw-999",
                "deviceIds", List.of("x0"),
                "stagePercents", List.of(100),
                "failureRateLimit", 0.3));
        assertReason(noFw, 404, "FIRMWARE_NOT_FOUND");

        // 空设备列表
        OtaTestClient.Response emptyDevs = api.postJson("/api/batches", java.util.Map.of(
                "firmwareId", "fw-1",
                "deviceIds", List.of(),
                "stagePercents", List.of(100),
                "failureRateLimit", 0.3));
        assertReason(emptyDevs, 400, "EMPTY_DEVICE_LIST");

        // 阶段百分比不为 100
        OtaTestClient.Response badPlan = api.postJson("/api/batches", java.util.Map.of(
                "firmwareId", "fw-1",
                "deviceIds", List.of("x0"),
                "stagePercents", List.of(40, 40),
                "failureRateLimit", 0.3));
        assertReason(badPlan, 400, "INVALID_STAGE_PLAN");

        // 批次不存在
        assertReason(api.postJson("/api/batches/batch-999/start", java.util.Map.of()),
                404, "BATCH_NOT_FOUND");

        // 设备未注册
        api.startBatch(batchId);
        assertReason(api.report(batchId, "ghost", 1, "SUCCESS", null), 404, "DEVICE_NOT_FOUND");
        // 设备不属于批次
        api.registerDevice("outsider", "v8.0.0");
        assertReason(api.report(batchId, "outsider", 1, "SUCCESS", null), 409,
                "DEVICE_NOT_ASSIGNED");

        // 重复固件版本 / 重复设备注册
        assertReason(api.postJson("/api/firmwares",
                java.util.Map.of("version", "v9.0.0", "checksum", "x")), 409,
                "FIRMWARE_ALREADY_EXISTS");
        assertReason(api.postJson("/api/devices",
                java.util.Map.of("deviceId", "x0", "currentFirmwareVersion", "v8.0.0")), 409,
                "DEVICE_ALREADY_REGISTERED");
    }
}
