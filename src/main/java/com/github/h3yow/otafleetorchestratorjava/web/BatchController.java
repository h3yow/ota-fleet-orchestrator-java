package com.github.h3yow.otafleetorchestratorjava.web;

import com.github.h3yow.otafleetorchestratorjava.dto.request.CreateBatchRequest;
import com.github.h3yow.otafleetorchestratorjava.dto.request.PauseRequest;
import com.github.h3yow.otafleetorchestratorjava.dto.request.RollbackRequest;
import com.github.h3yow.otafleetorchestratorjava.model.DeviceUpgrade;
import com.github.h3yow.otafleetorchestratorjava.model.UpgradeBatch;
import com.github.h3yow.otafleetorchestratorjava.service.UpgradeBatchService;
import com.github.h3yow.otafleetorchestratorjava.store.InMemoryStore;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/batches")
public class BatchController {

    private final UpgradeBatchService batchService;
    private final InMemoryStore store;

    public BatchController(UpgradeBatchService batchService, InMemoryStore store) {
        this.batchService = batchService;
        this.store = store;
    }

    @PostMapping
    public UpgradeBatch create(@RequestBody CreateBatchRequest request) {
        return batchService.create(request);
    }

    /** 分批推进 */
    @PostMapping("/{batchId}/advance")
    public UpgradeBatch advance(@PathVariable String batchId) {
        return batchService.advance(batchId);
    }

    @PostMapping("/{batchId}/pause")
    public UpgradeBatch pause(@PathVariable String batchId, @RequestBody(required = false) PauseRequest request) {
        return batchService.pause(batchId);
    }

    @PostMapping("/{batchId}/resume")
    public UpgradeBatch resume(@PathVariable String batchId) {
        return batchService.resume(batchId);
    }

    @PostMapping("/{batchId}/rollback")
    public UpgradeBatch rollback(@PathVariable String batchId,
                                 @RequestBody(required = false) RollbackRequest request) {
        return batchService.rollback(batchId, request == null ? null : request.reason());
    }

    @GetMapping("/{batchId}")
    public UpgradeBatch get(@PathVariable String batchId) {
        return batchService.get(batchId);
    }

    @GetMapping("/{batchId}/upgrades")
    public List<DeviceUpgrade> upgrades(@PathVariable String batchId) {
        batchService.get(batchId);
        return store.listUpgradesOfBatch(batchId);
    }

    @GetMapping
    public List<UpgradeBatch> list() {
        return batchService.list();
    }
}
