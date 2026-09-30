package com.github.h3yow.otafleetorchestratorjava.web;

import com.github.h3yow.otafleetorchestratorjava.dto.request.ProgressReportRequest;
import com.github.h3yow.otafleetorchestratorjava.dto.request.ReportResult;
import com.github.h3yow.otafleetorchestratorjava.service.ProgressService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/devices/{deviceId}/progress")
public class ProgressController {

    private final ProgressService progressService;

    public ProgressController(ProgressService progressService) {
        this.progressService = progressService;
    }

    /** 设备升级进度上报（幂等 + 乱序判定 + 失败阈值自动暂停） */
    @PostMapping
    public ReportResult report(@PathVariable String deviceId,
                               @RequestBody ProgressReportRequest request) {
        return progressService.report(deviceId, request);
    }
}
