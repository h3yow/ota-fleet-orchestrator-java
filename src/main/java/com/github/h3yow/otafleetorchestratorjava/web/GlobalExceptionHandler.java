package com.github.h3yow.otafleetorchestratorjava.web;

import com.github.h3yow.otafleetorchestratorjava.exception.DomainException;
import com.github.h3yow.otafleetorchestratorjava.exception.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(DomainException.class)
    public ResponseEntity<ErrorResponse> handleDomain(DomainException ex) {
        HttpStatus status = httpStatus(ex.getCode());
        log.warn("[reject] code={}, reason={}, message={}", ex.getCode(), ex.getCode().getReason(),
                ex.getMessage());
        return ResponseEntity.status(status)
                .body(new ErrorResponse(Instant.now(), ex.getCode(), ex.getMessage(), true));
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, IllegalArgumentException.class})
    public ResponseEntity<ErrorResponse> handleBadRequest(Exception ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse(Instant.now(), ErrorCode.INVALID_REQUEST,
                        ex.getMessage(), true));
    }

    /**
     * 参数缺失/非法、重复 -> 400；资源不存在 -> 404；
     * 其余前置条件不满足（状态机、过期、在途、终态等）-> 409 Conflict。
     */
    private HttpStatus httpStatus(ErrorCode code) {
        return switch (code) {
            case INVALID_REQUEST, FIRMWARE_VERSION_DUPLICATED, DEVICE_ALREADY_REGISTERED ->
                    HttpStatus.BAD_REQUEST;
            case FIRMWARE_NOT_FOUND, DEVICE_NOT_FOUND, BATCH_NOT_FOUND -> HttpStatus.NOT_FOUND;
            default -> HttpStatus.CONFLICT;
        };
    }
}
