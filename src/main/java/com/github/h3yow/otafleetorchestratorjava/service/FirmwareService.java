package com.github.h3yow.otafleetorchestratorjava.service;

import com.github.h3yow.otafleetorchestratorjava.domain.Batch;
import com.github.h3yow.otafleetorchestratorjava.domain.Firmware;
import com.github.h3yow.otafleetorchestratorjava.dto.CreateFirmwareRequest;
import com.github.h3yow.otafleetorchestratorjava.error.BusinessException;
import com.github.h3yow.otafleetorchestratorjava.error.RejectReason;
import com.github.h3yow.otafleetorchestratorjava.store.InMemoryStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/** 固件版本创建与查询。 */
@Service
public class FirmwareService {

    private static final Logger log = LoggerFactory.getLogger(FirmwareService.class);

    private final InMemoryStore store;

    public FirmwareService(InMemoryStore store) {
        this.store = store;
    }

    public Firmware create(CreateFirmwareRequest req) {
        if (req == null || req.version() == null || req.version().isBlank()) {
            throw new BusinessException(RejectReason.VALIDATION_FAILED, "version 不能为空");
        }
        return store.withLock(() -> {
            boolean duplicate = store.firmwares().values().stream()
                    .anyMatch(f -> f.getVersion().equals(req.version()));
            if (duplicate) {
                throw new BusinessException(RejectReason.FIRMWARE_ALREADY_EXISTS,
                        "固件版本 " + req.version() + " 已存在，重复创建被拒绝");
            }
            String id = store.nextFirmwareId();
            Firmware fw = new Firmware(id, req.version().trim(), req.checksum(), Instant.now());
            store.firmwares().put(id, fw);
            log.info("CREATE_FIRMWARE input=[{}] -> id={}", req.version(), id);
            return fw;
        });
    }

    public Firmware get(String firmwareId) {
        Firmware fw = store.firmwares().get(firmwareId);
        if (fw == null) {
            throw new BusinessException(RejectReason.FIRMWARE_NOT_FOUND,
                    "固件 " + firmwareId + " 不存在");
        }
        return fw;
    }

    public List<Firmware> list() {
        return List.copyOf(store.firmwares().values());
    }

    /** 仅供批次服务在锁内校验固件存在性。 */
    public void requireExistsLocked(String firmwareId) {
        if (!store.firmwares().containsKey(firmwareId)) {
            throw new BusinessException(RejectReason.FIRMWARE_NOT_FOUND,
                    "固件 " + firmwareId + " 不存在");
        }
    }

    /** 删除：被任何批次引用（即使已回滚）的固件不可删除。 */
    public void delete(String firmwareId) {
        store.withLock(() -> {
            Firmware fw = get(firmwareId);
            boolean referenced = store.batches().values().stream()
                    .map(Batch::getFirmwareId)
                    .anyMatch(firmwareId::equals);
            if (referenced) {
                throw new BusinessException(RejectReason.FIRMWARE_IN_USE,
                        "固件 " + firmwareId + " 已被批次引用，无法删除");
            }
            store.firmwares().remove(firmwareId);
            log.info("DELETE_FIRMWARE id={}", fw.getId());
        });
    }
}
