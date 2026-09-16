package com.example.server.controller;

import com.example.server.common.ErrorCode;
import com.example.server.common.Result;
import com.example.server.dto.AuthData;
import com.example.server.dto.AuthRequest;
import com.example.server.dto.AuthResponse;
import com.example.server.exception.BusinessException;
import com.example.server.service.AuthService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/user")
public class UserController {

    private final AuthService authService;

    public UserController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/register")
    public Result<AuthData> register(
            @Validated(AuthRequest.Register.class) @RequestBody AuthRequest request) {
        return toResult(authService.register(request));
    }

    @PostMapping("/login")
    public Result<AuthData> login(
            @Validated(AuthRequest.Login.class) @RequestBody AuthRequest request) {
        return toResult(authService.login(request));
    }

    @PostMapping("/logout")
    public Result<Void> logout(@RequestHeader("Authorization") String authorization) {
        authService.revokeSession(authorization);
        return Result.ok();
    }

    /**
     */
    private Result<AuthData> toResult(AuthResponse response) {
        if (response.code() != 200) {
            throw new BusinessException(mapAuthCode(response.code()), response.msg());
        }
        return Result.ok(new AuthData(response.userInfo(), response.token()));
    }

    private ErrorCode mapAuthCode(int code) {
        return switch (code) {
            case 400 -> ErrorCode.INVALID_ARGUMENT;
            case 401 -> ErrorCode.UNAUTHORIZED;
            case 409 -> ErrorCode.CONFLICT;
            case 429 -> ErrorCode.RATE_LIMITED;
            default -> ErrorCode.INTERNAL_ERROR;
        };
    }
}
