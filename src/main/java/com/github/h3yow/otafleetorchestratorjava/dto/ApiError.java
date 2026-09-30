package com.github.h3yow.otafleetorchestratorjava.dto;

import java.time.Instant;

/** 统一错误响应：reason 为可区分错误码，message 为人类可读解释。 */
public record ApiError(Instant time, String reason, String message) {
    public static ApiError of(String reason, String message) {
        return new ApiError(Instant.now(), reason, message);
    }
}
