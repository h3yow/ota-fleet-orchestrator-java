package com.github.h3yow.otafleetorchestratorjava.web;

import com.github.h3yow.otafleetorchestratorjava.dto.ApiError;
import com.github.h3yow.otafleetorchestratorjava.error.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** 将业务异常映射为带可区分错误码的 HTTP 响应。 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiError> handleBusiness(BusinessException ex) {
        log.warn("REJECT reason={} message={}", ex.getReason().code(), ex.getMessage());
        ApiError body = ApiError.of(ex.getReason().code(), ex.getMessage());
        return ResponseEntity.status(ex.getReason().httpStatus()).body(body);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleUnreadable(HttpMessageNotReadableException ex) {
        log.warn("REJECT reason=VALIDATION_FAILED message={}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiError.of("VALIDATION_FAILED", "请求体格式不合法: " + ex.getMessage()));
    }
}
