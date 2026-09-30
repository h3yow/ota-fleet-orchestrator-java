package com.github.h3yow.otafleetorchestratorjava.web;

import com.github.h3yow.otafleetorchestratorjava.dto.request.CreateFirmwareRequest;
import com.github.h3yow.otafleetorchestratorjava.model.Firmware;
import com.github.h3yow.otafleetorchestratorjava.service.FirmwareService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/firmwares")
public class FirmwareController {

    private final FirmwareService firmwareService;

    public FirmwareController(FirmwareService firmwareService) {
        this.firmwareService = firmwareService;
    }

    @PostMapping
    public Firmware create(@RequestBody CreateFirmwareRequest request) {
        return firmwareService.create(request.version());
    }

    @PostMapping("/{firmwareId}/release")
    public Firmware release(@PathVariable String firmwareId) {
        return firmwareService.release(firmwareId);
    }

    @GetMapping
    public List<Firmware> list() {
        return firmwareService.list();
    }
}
