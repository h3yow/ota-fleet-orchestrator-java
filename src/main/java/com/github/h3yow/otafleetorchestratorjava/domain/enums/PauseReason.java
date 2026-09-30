package com.github.h3yow.otafleetorchestratorjava.domain.enums;

public enum PauseReason {
    /** 人工暂停 */
    MANUAL,
    /** 失败率达到阈值，系统自动暂停 */
    FAILURE_THRESHOLD
}
