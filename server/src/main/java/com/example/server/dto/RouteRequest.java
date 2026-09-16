package com.example.server.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RouteRequest(
        @NotBlank(message = "Analysis goal is required")
        @Size(max = 500, message = "Analysis goal must not exceed 500 characters")
        String goal
) {
}
