package com.github.h3yow.otafleetorchestratorjava.domain;

/** 单台设备在一次升级中的状态。 */
public enum DeviceState {
    IDLE,
    PENDING,
    DOWNLOADING,
    INSTALLING,
    SUCCESS,
    FAILED,
    ROLLING_BACK,
    ROLLBACK_FAILED
}
