package com.github.h3yow.otafleetorchestratorjava.error;

/** 业务规则被违反时抛出，携带可区分的 {@link RejectReason}。 */
public class BusinessException extends RuntimeException {

    private final RejectReason reason;

    public BusinessException(RejectReason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public RejectReason getReason() {
        return reason;
    }
}
