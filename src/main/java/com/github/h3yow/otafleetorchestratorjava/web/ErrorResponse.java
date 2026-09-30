package com.github.h3yow.otafleetorchestratorjava.web;

import com.github.h3yow.otafleetorchestratorjava.exception.ErrorCode;

import java.time.Instant;

/**
 * 统一错误响应体。code 为可区分的拒绝原因，rejected=true 表示请求被业务规则拒绝。
 */
public record ErrorResponse(Instant timestamp,
                            ErrorCode code,
                            String message,
                            boolean rejected) {
}
