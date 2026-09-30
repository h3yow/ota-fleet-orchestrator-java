package com.github.h3yow.otafleetorchestratorjava.dto.request;

/**
 * 回滚批次。reason 仅用于审计说明。
 */
public record RollbackRequest(String reason) {
}
