package com.github.h3yow.otafleetorchestratorjava.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 测试客户端：封装 HTTP 调用并打印关键请求输入与响应，便于在测试日志中核验
 * 状态迁移与判定依据。
 */
public class OtaTestClient {

    private final ObjectMapper mapper = new ObjectMapper();
    private MockMvc mockMvc;

    public OtaTestClient(WebApplicationContext context) {
        this.mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    public record Response(int status, JsonNode body, String raw) {
    }

    public Response postJson(String path, Object body) throws Exception {
        String json = body == null ? "{}" : mapper.writeValueAsString(body);
        System.out.println(">>> POST " + path + " body=" + json);
        MockHttpServletRequestBuilder req = post(path)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.getBytes(StandardCharsets.UTF_8));
        return execute(req);
    }

    public Response getJson(String path) throws Exception {
        System.out.println(">>> GET  " + path);
        return execute(get(path));
    }

    private Response execute(MockHttpServletRequestBuilder req) throws Exception {
        MvcResult result = mockMvc.perform(req).andReturn();
        int status = result.getResponse().getStatus();
        String raw = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        System.out.println("<<< " + status + " " + raw);
        JsonNode node = raw.isBlank() ? mapper.createObjectNode() : mapper.readTree(raw);
        return new Response(status, node, raw);
    }

    // ---------- 高频操作便捷方法 ----------

    public String createFirmware(String version) throws Exception {
        return postJson("/api/firmwares",
                Map.of("version", version, "checksum", "sha256:" + version))
                .body().get("firmwareId").asText();
    }

    public String registerDevice(String id, String fwVersion) throws Exception {
        return postJson("/api/devices",
                Map.of("deviceId", id, "currentFirmwareVersion", fwVersion))
                .body().get("deviceId").asText();
    }

    public String createBatch(String firmwareId, List<String> deviceIds,
                              List<Integer> stagePercents, double failureLimit) throws Exception {
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("name", "batch-" + firmwareId);
        req.put("firmwareId", firmwareId);
        req.put("deviceIds", deviceIds);
        req.put("stagePercents", stagePercents);
        req.put("failureRateLimit", failureLimit);
        return postJson("/api/batches", req).body().get("batchId").asText();
    }

    public Response startBatch(String batchId) throws Exception {
        return postJson("/api/batches/" + batchId + "/start", null);
    }

    public Response pauseBatch(String batchId, String reason) throws Exception {
        return postJson("/api/batches/" + batchId + "/pause", Map.of("reason", reason));
    }

    public Response resumeBatch(String batchId) throws Exception {
        return postJson("/api/batches/" + batchId + "/resume", null);
    }

    public Response advanceBatch(String batchId) throws Exception {
        return postJson("/api/batches/" + batchId + "/advance", null);
    }

    public Response rollbackBatch(String batchId, String reason) throws Exception {
        return postJson("/api/batches/" + batchId + "/rollback", Map.of("reason", reason));
    }

    public Response report(String batchId, String deviceId, long seq,
                           String status, String reason) throws Exception {
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("seq", seq);
        req.put("status", status);
        if (reason != null) {
            req.put("reason", reason);
        }
        return postJson("/api/batches/" + batchId + "/devices/" + deviceId + "/reports", req);
    }

    public JsonNode batchView(String batchId) throws Exception {
        return getJson("/api/batches/" + batchId + "?events=100").body();
    }
}
