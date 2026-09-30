package com.github.h3yow.otafleetorchestratorjava.tests;

import com.github.h3yow.otafleetorchestratorjava.domain.enums.FirmwareStatus;
import com.github.h3yow.otafleetorchestratorjava.exception.DomainException;
import com.github.h3yow.otafleetorchestratorjava.exception.ErrorCode;
import com.github.h3yow.otafleetorchestratorjava.model.Firmware;
import com.github.h3yow.otafleetorchestratorjava.service.FirmwareService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 固件创建 / 发布 / 重复发布 / 空版本 的前置条件与错误原因区分。
 */
class FirmwareLifecycleTest {

    private final FirmwareService service = new FirmwareService(
            new com.github.h3yow.otafleetorchestratorjava.store.InMemoryStore());

    @Test
    void create_release_duplicateRelease_areRejectedWithDistinctReasons() {
        // 创建
        Firmware fw = service.create("v1.0.0");
        assertThat(fw.getStatus()).isEqualTo(FirmwareStatus.DRAFT);

        // 空版本
        assertThatThrownBy(() -> service.create(" "))
                .isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).getCode())
                .isEqualTo(ErrorCode.INVALID_REQUEST);

        // 版本重复
        assertThatThrownBy(() -> service.create("v1.0.0"))
                .isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).getCode())
                .isEqualTo(ErrorCode.FIRMWARE_VERSION_DUPLICATED);

        // 发布
        Firmware released = service.release(fw.getId());
        assertThat(released.getStatus()).isEqualTo(FirmwareStatus.RELEASED);
        assertThat(released.getReleasedAt()).isNotNull();

        // 重复发布
        assertThatThrownBy(() -> service.release(fw.getId()))
                .isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).getCode())
                .isEqualTo(ErrorCode.FIRMWARE_ALREADY_RELEASED);

        // 不存在
        assertThatThrownBy(() -> service.release("not-exist"))
                .isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).getCode())
                .isEqualTo(ErrorCode.FIRMWARE_NOT_FOUND);
    }
}
