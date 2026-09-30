package com.github.h3yow.otafleetorchestratorjava.tests;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 端到端 HTTP 集成：验证 200/400/404/409 状态码与可区分的 code 错误原因，
 * 覆盖正常分批发布、幂等重放、乱序、暂停/恢复门禁与回滚链路。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WebApiIntegrationTest {

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate rest;

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private HttpHeaders json() {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> body(ResponseEntity<Map> resp) {
        return resp.getBody();
    }

    @Test
    void fullApiFlow_statusCodesAndDistinctErrorCodes() {
        // 1. 固件
        var fw = rest.postForEntity(url("/api/firmwares"),
                new HttpEntity<>(Map.of("version", "fw-v1"), json()), Map.class);
        assertThat(fw.getStatusCode()).isEqualTo(HttpStatus.OK);
        String firmwareId = (String) body(fw).get("id");

        var dup = rest.postForEntity(url("/api/firmwares"),
                new HttpEntity<>(Map.of("version", "fw-v1"), json()), Map.class);
        assertThat(dup.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(body(dup).get("code")).isEqualTo("FIRMWARE_VERSION_DUPLICATED");

        // 2. 设备
        for (String d : List.of("wdev-1", "wdev-2")) {
            var reg = rest.postForEntity(url("/api/devices"),
                    new HttpEntity<>(Map.of("deviceId", d, "currentFirmwareVersion", "old-v1"), json()),
                    Map.class);
            assertThat(reg.getStatusCode()).isEqualTo(HttpStatus.OK);
        }
        var dupDev = rest.postForEntity(url("/api/devices"),
                new HttpEntity<>(Map.of("deviceId", "wdev-1", "currentFirmwareVersion", "old-v1"), json()),
                Map.class);
        assertThat(dupDev.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(body(dupDev).get("code")).isEqualTo("DEVICE_ALREADY_REGISTERED");
        assertThat(rest.postForEntity(url("/api/devices/wdev-1/heartbeat"), null, Map.class)
                .getStatusCode()).isEqualTo(HttpStatus.OK);

        // 3. 未发布建批次 -> 409
        var unrel = rest.postForEntity(url("/api/batches"),
                new HttpEntity<>(Map.of("firmwareId", firmwareId, "batchSize", 1,
                        "failureThresholdPercent", 50), json()), Map.class);
        assertThat(unrel.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(body(unrel).get("code")).isEqualTo("FIRMWARE_NOT_RELEASED");
        rest.postForEntity(url("/api/firmwares/" + firmwareId + "/release"), null, Map.class);

        // 4. 建批次（batchSize=1）并推进第一批
        var created = rest.postForEntity(url("/api/batches"),
                new HttpEntity<>(Map.of("firmwareId", firmwareId, "batchSize", 1,
                        "failureThresholdPercent", 50), json()), Map.class);
        String batchId = (String) body(created).get("id");
        rest.postForEntity(url("/api/batches/" + batchId + "/advance"), null, Map.class);

        // 在途门禁
        var blocked = rest.postForEntity(url("/api/batches/" + batchId + "/advance"), null, Map.class);
        assertThat(blocked.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(body(blocked).get("code")).isEqualTo("BATCH_HAS_INFLIGHT_DEVICES");

        // dev-1 成功 + 幂等重放
        report("wdev-1", batchId, "DOWNLOADING", 1, "w1d");
        report("wdev-1", batchId, "INSTALLING", 2, "w1i");
        var ok = report("wdev-1", batchId, "SUCCESS", 3, "w1s");
        assertThat(body(ok).get("deviceStatus")).isEqualTo("SUCCESS");
        var replay = report("wdev-1", batchId, "SUCCESS", 3, "w1s");
        assertThat(body(replay).get("duplicate")).isEqualTo(true);

        // 推进第二批：dev-2
        rest.postForEntity(url("/api/batches/" + batchId + "/advance"), null, Map.class);

        // 乱序：先接受 seq=10 的 DOWNLOADING，再来 seq=5 -> STALE_SEQUENCE
        report("wdev-2", batchId, "DOWNLOADING", 10, "w2d10");
        var stale = report("wdev-2", batchId, "INSTALLING", 5, "w2i5");
        assertThat(stale.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(body(stale).get("code")).isEqualTo("STALE_SEQUENCE");

        // 非法跳转：PENDING 已在 DOWNLOADING，直接 SUCCESS -> ILLEGAL_STATUS_TRANSITION
        var jump = report("wdev-2", batchId, "SUCCESS", 11, "w2jump");
        assertThat(jump.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(body(jump).get("code")).isEqualTo("ILLEGAL_STATUS_TRANSITION");

        // dev-2 正常收尾
        report("wdev-2", batchId, "INSTALLING", 11, "w2i11");
        report("wdev-2", batchId, "SUCCESS", 12, "w2s12");
        rest.postForEntity(url("/api/batches/" + batchId + "/advance"), null, Map.class);
        var done = rest.getForEntity(url("/api/batches/" + batchId), Map.class);
        assertThat(body(done).get("status")).isEqualTo("COMPLETED");

        // 已完成批次不能暂停 -> 409 BATCH_ALREADY_TERMINAL
        var pauseDone = rest.postForEntity(url("/api/batches/" + batchId + "/pause"),
                new HttpEntity<>(jsonHeaders(), json()), Map.class);
        assertThat(pauseDone.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(body(pauseDone).get("code")).isEqualTo("BATCH_ALREADY_TERMINAL");

        // 5. 新批次验证 暂停中不能推进 / 恢复 / 回滚
        var created2 = rest.postForEntity(url("/api/batches"),
                new HttpEntity<>(Map.of("firmwareId", firmwareId, "batchSize", 2,
                        "failureThresholdPercent", 50), json()), Map.class);
        // 设备都已是 fw-v1，无合格设备 -> BATCH_NO_ELIGIBLE_DEVICE
        assertThat(created2.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(body(created2).get("code")).isEqualTo("BATCH_NO_ELIGIBLE_DEVICE");

        // 404
        var nf = rest.getForEntity(url("/api/batches/no-such-batch"), Map.class);
        assertThat(nf.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(body(nf).get("code")).isEqualTo("BATCH_NOT_FOUND");
    }

    private HttpHeaders jsonHeaders() {
        return json();
    }

    private ResponseEntity<Map> report(String device, String batch, String status, long seq, String req) {
        return rest.postForEntity(url("/api/devices/" + device + "/progress"),
                new HttpEntity<>(Map.of("batchId", batch, "status", status,
                        "seq", seq, "requestId", req), json()), Map.class);
    }
}
