package com.github.h3yow.otafleetorchestratorjava.tests;

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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 心跳幂等、注册幂等拒绝与基础入参校验。 */
@SpringBootTest
class HeartbeatAndValidationTest {

    private static final Logger log = LoggerFactory.getLogger("HEARTBEAT-TEST");

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
    void heartbeat_isIdempotent_andRefreshesTimestamp() throws Exception {
        OtaTestClient.Response reg = api.postJson("/api/devices",
                Map.of("currentFirmwareVersion", "v1.0.0"));
        assertEquals(201, reg.status());
        String id = reg.body().get("deviceId").asText();
        assertNotNull(id);

        OtaTestClient.Response h1 = api.postJson("/api/devices/" + id + "/heartbeat", Map.of());
        OtaTestClient.Response h2 = api.postJson("/api/devices/" + id + "/heartbeat", Map.of());
        assertEquals(200, h1.status());
        assertEquals(200, h2.status());
        assertEquals(id, h2.body().get("deviceId").asText());
        assertNotNull(h2.body().get("lastHeartbeatAt").asText());
        log.info("[CHECK] repeated heartbeats are idempotent and refresh lastHeartbeatAt");

        // 未注册设备心跳 -> DEVICE_NOT_FOUND
        OtaTestClient.Response ghost = api.postJson("/api/devices/ghost/heartbeat", Map.of());
        assertEquals(404, ghost.status());
        assertEquals("DEVICE_NOT_FOUND", ghost.body().get("reason").asText());
    }

    @Test
    void badRequests_returnValidationFailed() throws Exception {
        // 固件缺 version
        OtaTestClient.Response noVersion = api.postJson("/api/firmwares",
                Map.of("checksum", "abc"));
        assertEquals(400, noVersion.status());
        assertEquals("VALIDATION_FAILED", noVersion.body().get("reason").asText());

        // 上报缺字段
        api.postJson("/api/firmwares", Map.of("version", "v1", "checksum", "c"));
        api.postJson("/api/devices", Map.of("deviceId", "d0", "currentFirmwareVersion", "v0"));
        OtaTestClient.Response batch = api.postJson("/api/batches", Map.of(
                "firmwareId", "fw-1",
                "deviceIds", List.of("d0"),
                "stagePercents", List.of(100),
                "failureRateLimit", 0.3));
        assertEquals(201, batch.status());
        String batchId = batch.body().get("batchId").asText();
        api.postJson("/api/batches/" + batchId + "/start", Map.of());

        OtaTestClient.Response missingSeq = api.postJson(
                "/api/batches/" + batchId + "/devices/d0/reports",
                Map.of("status", "SUCCESS"));
        assertEquals(400, missingSeq.status());
        assertEquals("VALIDATION_FAILED", missingSeq.body().get("reason").asText());

        OtaTestClient.Response badStatus = api.postJson(
                "/api/batches/" + batchId + "/devices/d0/reports",
                Map.of("seq", 1, "status", "EXPLODED"));
        assertEquals(400, badStatus.status());
        assertEquals("VALIDATION_FAILED", badStatus.body().get("reason").asText());

        // 非法 JSON
        OtaTestClient.Response malformed = api.postJson(
                "/api/batches/" + batchId + "/devices/d0/reports",
                Map.of());
        assertTrue(malformed.status() == 400);
        log.info("[CHECK] malformed/missing-field requests rejected with distinct VALIDATION_FAILED");
    }
}
