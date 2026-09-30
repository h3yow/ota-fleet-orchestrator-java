package com.github.h3yow.otafleetorchestratorjava.dto.request;

/**
 * 人工暂停批次。reason 固定记为 MANUAL。
 */
public record PauseRequest(String reason) {
}
