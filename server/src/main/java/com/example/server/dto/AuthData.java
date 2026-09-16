package com.example.server.dto;

/**
 *
 */
public record AuthData(AuthResponse.UserInfo userInfo, String token) {
}
