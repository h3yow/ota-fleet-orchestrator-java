package com.github.h3yow.otafleetorchestratorjava.service;

import com.github.h3yow.otafleetorchestratorjava.domain.enums.FirmwareStatus;
import com.github.h3yow.otafleetorchestratorjava.exception.DomainException;
import com.github.h3yow.otafleetorchestratorjava.exception.ErrorCode;
import com.github.h3yow.otafleetorchestratorjava.model.Firmware;
import com.github.h3yow.otafleetorchestratorjava.store.InMemoryStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class FirmwareService {

    private static final Logger log = LoggerFactory.getLogger(FirmwareService.class);

    private final InMemoryStore store;

    public FirmwareService(InMemoryStore store) {
        this.store = store;
    }

    /**
     * 创建固件（DRAFT）。
     * 前置条件：version 非空；同版本号不得重复。
     */
    public Firmware create(String version) {
        if (version == null || version.isBlank()) {
            throw new DomainException(ErrorCode.INVALID_REQUEST, "固件版本号不能为空");
        }
        if (store.findFirmwareByVersion(version).isPresent()) {
            throw new DomainException(ErrorCode.FIRMWARE_VERSION_DUPLICATED,
                    "固件版本已存在: " + version);
        }
        Firmware firmware = new Firmware(UUID.randomUUID().toString(), version, Instant.now());
        store.putFirmware(firmware);
        log.info("[firmware-create] input={} -> id={}, status=DRAFT", version, firmware.getId());
        return firmware;
    }

    /**
     * 发布固件 DRAFT -> RELEASED。
     * 前置条件：固件存在；当前必须为 DRAFT。
     */
    public Firmware release(String firmwareId) {
        Firmware firmware = store.findFirmware(firmwareId)
                .orElseThrow(() -> new DomainException(ErrorCode.FIRMWARE_NOT_FOUND,
                        "固件不存在: " + firmwareId));
        if (firmware.getStatus() == FirmwareStatus.RELEASED) {
            throw new DomainException(ErrorCode.FIRMWARE_ALREADY_RELEASED,
                    "固件已发布，不能重复发布: " + firmwareId);
        }
        firmware.setStatus(FirmwareStatus.RELEASED);
        firmware.setReleasedAt(Instant.now());
        log.info("[firmware-release] id={}, version={}, transition=DRAFT->RELEASED",
                firmwareId, firmware.getVersion());
        return firmware;
    }

    public Firmware get(String firmwareId) {
        return store.findFirmware(firmwareId)
                .orElseThrow(() -> new DomainException(ErrorCode.FIRMWARE_NOT_FOUND,
                        "固件不存在: " + firmwareId));
    }

    public List<Firmware> list() {
        return store.listFirmwares();
    }
}
