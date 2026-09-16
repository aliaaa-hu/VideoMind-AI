package com.example.server.common;

/**
 *
 *
 */
public record Result<T>(int code, String message, T data) {

    public static final int SUCCESS_CODE = 0;
    private static final String SUCCESS_MESSAGE = "success";

    public static <T> Result<T> ok(T data) {
        return new Result<>(SUCCESS_CODE, SUCCESS_MESSAGE, data);
    }

    public static <T> Result<T> ok() {
        return new Result<>(SUCCESS_CODE, SUCCESS_MESSAGE, null);
    }

    public static <T> Result<T> error(int code, String message) {
        return new Result<>(code, message, null);
    }

    public static <T> Result<T> error(ErrorCode errorCode, String message) {
        return new Result<>(errorCode.code(), message, null);
    }
}
