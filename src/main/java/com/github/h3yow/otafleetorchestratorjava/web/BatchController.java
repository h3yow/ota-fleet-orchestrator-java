package com.github.h3yow.otafleetorchestratorjava.web;

import com.github.h3yow.otafleetorchestratorjava.domain.Batch;
import com.github.h3yow.otafleetorchestratorjava.dto.BatchView;
import com.github.h3yow.otafleetorchestratorjava.dto.CreateBatchRequest;
import com.github.h3yow.otafleetorchestratorjava.dto.ReportRequest;
import com.github.h3yow.otafleetorchestratorjava.service.BatchService;
import com.github.h3yow.otafleetorchestratorjava.service.ReportService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** 升级批次编排与设备上报接口。 */
@RestController
@RequestMapping("/api/batches")
public class BatchController {

    private static final int DEFAULT_EVENT_LIMIT = 20;
    private static final int MAX_EVENT_LIMIT = 200;

    private final BatchService batchService;
    private final ReportService reportService;

    public BatchController(BatchService batchService, ReportService reportService) {
        this.batchService = batchService;
        this.reportService = reportService;
    }

    @PostMapping
    public ResponseEntity<BatchView> create(@RequestBody CreateBatchRequest request) {
        Batch b = batchService.create(request);
        return ResponseEntity.status(201).body(batchService.view(b.getId(), DEFAULT_EVENT_LIMIT));
    }

    @GetMapping
    public List<BatchView> list(@RequestParam(defaultValue = "5") int events) {
        return batchService.list(clampEvents(events));
    }

    @GetMapping("/{batchId}")
    public BatchView get(@PathVariable String batchId,
                         @RequestParam(defaultValue = "20") int events) {
        return batchService.view(batchId, clampEvents(events));
    }

    @PostMapping("/{batchId}/start")
    public BatchView start(@PathVariable String batchId) {
        batchService.start(batchId);
        return batchService.view(batchId, DEFAULT_EVENT_LIMIT);
    }

    @PostMapping("/{batchId}/pause")
    public BatchView pause(@PathVariable String batchId,
                           @RequestBody(required = false) Map<String, String> body) {
        String reason = body == null ? null : body.get("reason");
        batchService.pause(batchId, reason);
        return batchService.view(batchId, DEFAULT_EVENT_LIMIT);
    }

    @PostMapping("/{batchId}/resume")
    public BatchView resume(@PathVariable String batchId) {
        batchService.resume(batchId);
        return batchService.view(batchId, DEFAULT_EVENT_LIMIT);
    }

    @PostMapping("/{batchId}/advance")
    public BatchView advance(@PathVariable String batchId) {
        batchService.advance(batchId);
        return batchService.view(batchId, DEFAULT_EVENT_LIMIT);
    }

    @PostMapping("/{batchId}/rollback")
    public BatchView rollback(@PathVariable String batchId,
                              @RequestBody(required = false) Map<String, String> body) {
        String reason = body == null ? null : body.get("reason");
        batchService.rollback(batchId, reason);
        return batchService.view(batchId, DEFAULT_EVENT_LIMIT);
    }

    /** 设备进度上报：同一设备重复/乱序请求按幂等与过期规则处理。 */
    @PostMapping("/{batchId}/devices/{deviceId}/reports")
    public ReportService.ReportOutcome report(@PathVariable String batchId,
                                              @PathVariable String deviceId,
                                              @RequestBody ReportRequest request) {
        return reportService.report(batchId, deviceId, request);
    }

    private int clampEvents(int events) {
        if (events < 1) {
            return 1;
        }
        return Math.min(events, MAX_EVENT_LIMIT);
    }
}
