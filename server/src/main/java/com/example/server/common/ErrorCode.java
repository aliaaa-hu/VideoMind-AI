package com.example.server.common;

import org.springframework.http.HttpStatus;

/**
 *
 */
public enum ErrorCode {

    INVALID_ARGUMENT(40000, HttpStatus.BAD_REQUEST),
    VALIDATION_FAILED(40001, HttpStatus.BAD_REQUEST),
    UNAUTHORIZED(40100, HttpStatus.UNAUTHORIZED),
    FORBIDDEN(40300, HttpStatus.FORBIDDEN),
    NOT_FOUND(40400, HttpStatus.NOT_FOUND),
    CONFLICT(40900, HttpStatus.CONFLICT),
    UNPROCESSABLE(42200, HttpStatus.UNPROCESSABLE_ENTITY),
    RATE_LIMITED(42900, HttpStatus.TOO_MANY_REQUESTS),
    INTERNAL_ERROR(50000, HttpStatus.INTERNAL_SERVER_ERROR),
    SERVICE_UNAVAILABLE(50300, HttpStatus.SERVICE_UNAVAILABLE);

    private final int code;
    private final HttpStatus httpStatus;

    ErrorCode(int code, HttpStatus httpStatus) {
        this.code = code;
        this.httpStatus = httpStatus;
    }

    public int code() {
        return code;
    }

    public HttpStatus httpStatus() {
        return httpStatus;
    }
}
