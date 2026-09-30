package com.github.h3yow.otafleetorchestratorjava.tests;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.h3yow.otafleetorchestratorjava.store.InMemoryStore;
import com.github.h3yow.otafleetorchestratorjava.support.OtaTestClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.web.context.WebApplicationContext;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 正常发布流程：创建固件/设备 -> 创建三阶段批次 -> 启动 ->
 * 阶段 0 全部成功后自动下发阶段 1 -> ... -> 全部成功后批次 COMPLETED。
 */
@SpringBootTest
class NormalRolloutFlowTest {

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
    void threeStagesRollout_completesWithAutomaticAdvance() throws Exception {
        String fw = api.createFirmware("v2.0.0");
        // 10 台设备，初始版本 v1.0.0
        for (int i = 0; i < 10; i++) {
            api.registerDevice("d" + i, "v1.0.0");
        }
        String batchId;
        batchId = api.createBatch(fw,
                List.of("d0", "d1", "d2", "d3", "d4", "d5", "d6", "d7", "d8", "d9"),
                List.of(20, 30, 50), 0.3);
        api.startBatch(batchId);

        JsonNode view = api.batchView(batchId);
        assertEquals("ROLLING", view.get("status").asText());
        assertEquals(0, view.get("activeStage").asInt());
        // 第 0 阶段按设备 ID 排序后取前 20% = 2 台
        assertEquals(2, view.get("stages").get(0).get("size").asInt());
        assertEquals(3, view.get("stages").get(1).get("size").asInt());
        assertEquals(5, view.get("stages").get(2).get("size").asInt());
        System.out.println("[CHECK] stage sizes = [2,3,5], stage0 dispatched");

        // 阶段 0 两台成功 -> 自动推进到阶段 1
        reportSuccess(batchId, "d0", 1);
        reportSuccess(batchId, "d1", 1);
        view = api.batchView(batchId);
        assertEquals("ROLLING", view.get("status").asText());
        assertEquals(1, view.get("activeStage").asInt(), "阶段0全部成功后应自动推进");
        assertEquals(2, view.get("successCount").asInt());

        // 阶段 1 三台成功 -> 自动推进到阶段 2
        for (String d : List.of("d2", "d3", "d4")) {
            reportSuccess(batchId, d, 1);
        }
        view = api.batchView(batchId);
        assertEquals(2, view.get("activeStage").asInt());

        // 阶段 2：先发一个中间进度，再终态成功，中间进度不计入成功/失败
        OtaTestClient.Response progress = api.report(batchId, "d5", 1, "DOWNLOADING", null);
        assertEquals(200, progress.status());
        assertEquals("DOWNLOADING", progress.body().get("deviceState").asText());
        api.report(batchId, "d5", 2, "INSTALLING", null);
        reportSuccess(batchId, "d5", 3);
        for (String d : List.of("d6", "d7", "d8", "d9")) {
            reportSuccess(batchId, d, 1);
        }

        view = api.batchView(batchId);
        assertEquals("COMPLETED", view.get("status").asText(),
                "全部设备成功后批次应完成");
        assertEquals(10, view.get("successCount").asInt());
        assertEquals(0, view.get("failureCount").asInt());
        assertEquals(0.0, view.get("failureRate").asDouble(), 1e-9);

        // 成功设备的当前固件版本应已升级
        JsonNode device = api.getJson("/api/devices/d5").body();
        assertEquals("v2.0.0", device.get("currentFirmwareVersion").asText());

        // 事件流应包含创建、启动、每次推进与完成
        String events = view.get("recentEvents").toString();
        assertTrue(events.contains("BATCH_CREATED"));
        assertTrue(events.contains("STAGE_ADVANCED"));
        assertTrue(events.contains("BATCH_COMPLETED"));
        System.out.println("[CHECK] batch COMPLETED, 10/10 success, device d5 upgraded to v2.0.0");
    }

    private void reportSuccess(String batchId, String deviceId, long seq) throws Exception {
        OtaTestClient.Response r = api.report(batchId, deviceId, seq, "SUCCESS", null);
        assertEquals(200, r.status(), "成功上报应被接受");
    }
}
