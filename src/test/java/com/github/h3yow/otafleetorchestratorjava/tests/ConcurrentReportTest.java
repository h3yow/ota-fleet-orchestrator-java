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
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 并发上报：多线程同时上报成功/失败/重复请求，
 * 验证计数不重复、阶段不重复推进、阈值不漏判、最终状态一致。
 */
@SpringBootTest
class ConcurrentReportTest {

    private static final Logger log = LoggerFactory.getLogger("CONCURRENT-TEST");

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
    void concurrentSuccessReports_dispatchAndAdvanceExactlyOnce() throws Exception {
        String fw = api.createFirmware("v2.0.0");
        for (int i = 0; i < 10; i++) {
            api.registerDevice("d" + i, "v1.0.0");
        }
        String batchId = api.createBatch(fw,
                List.of("d0", "d1", "d2", "d3", "d4", "d5", "d6", "d7", "d8", "d9"),
                List.of(20, 30, 50), 0.3);
        api.startBatch(batchId);

        // 阶段 0 两台设备并发上报成功：只能触发一次阶段推进
        concurrentRun(2, t -> {
            String device = "d" + t;
            OtaTestClient.Response r = api.report(batchId, device, 1, "SUCCESS", null);
            assertEquals(200, r.status());
        });
        JsonNode view = api.batchView(batchId);
        assertEquals("ROLLING", view.get("status").asText());
        assertEquals(1, view.get("activeStage").asInt(), "阶段0并发收齐后必须恰好推进一次");
        assertEquals(2, view.get("successCount").asInt());
        log.info("[CHECK] stage0 concurrent success -> advanced exactly once, activeStage=1");

        // 全量 10 台并发成功（阶段1、阶段2的设备此时阶段未激活，前3台立即成功，后5台会被拒，
        // 这里改为按推进节奏循环：每轮对当前阶段设备并发上报）
        for (int stage = 1; stage <= 2; stage++) {
            JsonNode snapshot = api.batchView(batchId);
            int active = snapshot.get("activeStage").asInt();
            int size = snapshot.get("stages").get(active).get("size").asInt();
            int base = deviceBase(active);
            concurrentRun(size, t -> {
                String device = "d" + (base + t);
                OtaTestClient.Response r = api.report(batchId, device, 1, "SUCCESS", null);
                assertEquals(200, r.status());
            });
        }

        view = api.batchView(batchId);
        assertEquals("COMPLETED", view.get("status").asText());
        assertEquals(10, view.get("successCount").asInt());
        assertEquals(0, view.get("failureCount").asInt());
        // 每个阶段下发事件只能出现一次
        long dispatchEvents = count(view.get("recentEvents"), "STAGE_DISPATCHED");
        assertEquals(3, dispatchEvents, "三个阶段必须各下发一次，不得重复下发");
        long advanceEvents = count(view.get("recentEvents"), "STAGE_ADVANCED");
        assertEquals(2, advanceEvents, "只能有两次阶段推进");
        log.info("[CHECK] concurrent all-success completed: 10 success, 3 dispatches, 2 advances");
    }

    @Test
    void concurrentMixedSuccessAndFailure_thresholdJudgedOnceAndRollsBack() throws Exception {
        // 单阶段 20 台，失败阈值 0.3。
        // 为了确定性地制造"多个并发请求挤在临界点前后"的场景，分两波：
        // 波1：5 台并发成功（失败率仍为 0）；
        // 波2：与波1完成后立刻由 15 个线程同时发起（6 FAILED + 9 SUCCESS），
        //      第 3 个失败被处理时 5成功+3失败 => 0.375 >= 0.3，恰好触发回滚。
        String fw = api.createFirmware("v3.1.0");
        int total = 20;
        for (int i = 0; i < total; i++) {
            api.registerDevice("dev" + i, "v3.0.0");
        }
        List<String> ids = new java.util.ArrayList<>();
        for (int i = 0; i < total; i++) {
            ids.add("dev" + i);
        }
        String batchId = api.createBatch(fw, ids, List.of(100), 0.3);
        api.startBatch(batchId);

        // 波1：dev0-dev4 并发成功
        concurrentRun(5, t -> {
            OtaTestClient.Response r = api.report(batchId, "dev" + t, 1, "SUCCESS", null);
            assertEquals(200, r.status());
        });

        // 波2：dev5-dev19（15 个）同时发起，dev5-dev10 为失败（6台），其余成功
        ConcurrentLinkedQueue<Integer> statuses = new ConcurrentLinkedQueue<>();
        concurrentRun(total - 5, t -> {
            int deviceNo = 5 + t;
            boolean fail = deviceNo <= 10;
            String kind = fail ? "FAILED" : "SUCCESS";
            String reason = fail ? "simulated failure " + deviceNo : null;
            OtaTestClient.Response r = api.report(batchId, "dev" + deviceNo, 1, kind, reason);
            statuses.add(r.status());
        });

        // 触发回滚的临界请求之后到达的请求，会以 BATCH_FINISHED/ROLLBACK_IN_PROGRESS 被拒绝；
        // 被接受(200)的请求计数必须精确、无重复无丢失，且至少有 3 个请求进入了计数。
        long accepted = statuses.stream().filter(s -> s == 200).count();
        long rejected = statuses.size() - accepted;
        log.info("[CHECK] mixed concurrent reports wave2: accepted={}, rejected={}",
                accepted, rejected);
        assertTrue(accepted >= 3, "临界点前应至少有3个并发请求被计入，实际 " + accepted);
        for (int s : statuses) {
            assertTrue(s == 200 || s == 409, "并发上报只应 200 或 409，实际 " + s);
        }

        JsonNode view = api.batchView(batchId);
        assertEquals("ROLLED_BACK", view.get("status").asText(),
                "失败率达到阈值后必须最终进入 ROLLED_BACK");
        // 回滚快照中的终态计数必须精确：波1(5成功) + 波2被接受的请求，恰好各计数一次
        int counted = view.get("successCount").asInt() + view.get("failureCount").asInt();
        assertEquals(5 + accepted, counted,
                "被接受的请求必须恰好计数一次，禁止重复计数或漏判");
        assertTrue(view.get("failureRate").asDouble() >= 0.3 - 1e-9,
                "触发时失败率必须 >= 0.3，实际 " + view.get("failureRate").asDouble());

        long rollbackEvents = count(view.get("recentEvents"), "ROLLBACK_TRIGGERED");
        assertEquals(1, rollbackEvents, "自动回滚只能触发一次，禁止重复推进/重复回滚");
        long finishEvents = count(view.get("recentEvents"), "ROLLBACK_FINISHED");
        assertEquals(1, finishEvents);

        // 所有设备必须回到一致的 IDLE 状态，可继续操作
        for (JsonNode d : view.get("devices")) {
            assertEquals("IDLE", d.get("state").asText(),
                    "回滚后设备 " + d.get("deviceId").asText() + " 必须为 IDLE");
        }
        log.info("[CHECK] rollback consistency verified: batch=ROLLED_BACK, all devices IDLE, counted={}", counted);
    }

    /** 按 20%/30%/50% 且设备按 ID 排序的分配规则，返回阶段首台设备的序号。 */
    private int deviceBase(int stage) {
        return switch (stage) {
            case 0 -> 0;
            case 1 -> 2;
            case 2 -> 5;
            default -> throw new IllegalStateException();
        };
    }

    private void concurrentRun(int n, ThrowingConsumer<Integer> action) throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(Math.max(2, n));
        CountDownLatch ready = new CountDownLatch(n);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(n);
        ConcurrentLinkedQueue<Throwable> errors = new ConcurrentLinkedQueue<>();
        for (int i = 0; i < n; i++) {
            final int idx = i;
            pool.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    action.accept(idx);
                } catch (Throwable e) {
                    errors.add(e);
                } finally {
                    done.countDown();
                }
            });
        }
        ready.await();
        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS), "并发任务应在超时前完成");
        pool.shutdownNow();
        if (!errors.isEmpty()) {
            errors.forEach(Throwable::printStackTrace);
            throw new AssertionError("并发任务出现异常: " + errors.peek());
        }
    }

    private long count(JsonNode events, String typePrefix) {
        long n = 0;
        for (JsonNode e : events) {
            if (e.asText().contains(typePrefix)) {
                n++;
            }
        }
        return n;
    }

    @FunctionalInterface
    private interface ThrowingConsumer<T> {
        void accept(T t) throws Exception;
    }
}
