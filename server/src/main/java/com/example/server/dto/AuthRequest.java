package com.example.server.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 *
 */
public record AuthRequest(
        @NotBlank(message = "Username is required", groups = {Login.class, Register.class})
        @Size(min = 3, max = 32, message = "Username must be 3-32 characters", groups = Register.class)
        @Size(max = 32, message = "Username must not exceed 32 characters", groups = Login.class)
        String username,

        @NotBlank(message = "Password is required", groups = {Login.class, Register.class})
        @Size(max = 128, message = "Password must not exceed 128 characters", groups = {Login.class, Register.class})
        @Size(min = 8, message = "Password must be at least 8 characters", groups = Register.class)
        String password,

        @Size(max = 50, message = "Nickname must not exceed 50 characters", groups = Register.class)
        String nickname
) {
    public interface Login { }

    public interface Register { }
}
