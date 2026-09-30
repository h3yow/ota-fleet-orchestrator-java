package com.github.h3yow.otafleetorchestratorjava.web;

import com.github.h3yow.otafleetorchestratorjava.domain.Firmware;
import com.github.h3yow.otafleetorchestratorjava.dto.ApiError;
import com.github.h3yow.otafleetorchestratorjava.dto.CreateFirmwareRequest;
import com.github.h3yow.otafleetorchestratorjava.service.FirmwareService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** 固件版本管理接口。 */
@RestController
@RequestMapping("/api/firmwares")
public class FirmwareController {

    private final FirmwareService firmwareService;

    public FirmwareController(FirmwareService firmwareService) {
        this.firmwareService = firmwareService;
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody CreateFirmwareRequest request) {
        Firmware fw = firmwareService.create(request);
        return ResponseEntity.status(201).body(toView(fw));
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        return firmwareService.list().stream().map(this::toView).toList();
    }

    @GetMapping("/{firmwareId}")
    public Map<String, Object> get(@PathVariable String firmwareId) {
        return toView(firmwareService.get(firmwareId));
    }

    @DeleteMapping("/{firmwareId}")
    public ResponseEntity<ApiError> delete(@PathVariable String firmwareId) {
        firmwareService.delete(firmwareId);
        return ResponseEntity.noContent().build();
    }

    private Map<String, Object> toView(Firmware fw) {
        return Map.of(
                "firmwareId", fw.getId(),
                "version", fw.getVersion(),
                "checksum", fw.getChecksum() == null ? "" : fw.getChecksum(),
                "createdAt", fw.getCreatedAt()
        );
    }
}
