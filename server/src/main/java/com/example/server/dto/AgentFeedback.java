package com.example.server.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

public record AgentFeedback(
        @NotNull(message = "mediaId is required")
        Long mediaId,

        @NotBlank(message = "Analysis goal is required")
        @Size(max = 500, message = "Analysis goal must not exceed 500 characters")
        String goal,

        String mode,

        Integer rating,

        @Size(max = 64, message = "Error type must not exceed 64 characters")
        String errorType,

        @Size(max = 2000, message = "Feedback must not exceed 2000 characters")
        String comment,

        @Size(max = 500, message = "Revised analysis goal must not exceed 500 characters")
        String correctedGoal,

        @Size(max = 5, message = "At most 5 revised tasks are allowed")
        List<@Size(max = 500, message = "Each revised task must not exceed 500 characters") String> correctedTasks,

        @PositiveOrZero(message = "Evidence timestamp must not be negative")
        Long evidenceTimestamp,

        Boolean evidenceAccepted,

        Instant createdAt
) {
    public AgentFeedback normalized() {
        return normalized(AnalysisMode.fromNullable(mode));
    }

    public AgentFeedback normalized(AnalysisMode analysisMode) {
        return new AgentFeedback(
                mediaId,
                goal == null ? null : goal.trim(),
                (analysisMode == null ? AnalysisMode.GENERAL : analysisMode).name(),
                rating,
                errorType == null ? null : errorType.trim(),
                comment == null ? null : comment.trim(),
                correctedGoal == null ? null : correctedGoal.trim(),
                correctedTasks == null ? List.of() : correctedTasks.stream()
                        .filter(task -> task != null && !task.isBlank())
                        .map(String::trim)
                        .toList(),
                evidenceTimestamp,
                evidenceAccepted,
                createdAt == null ? Instant.now() : createdAt
        );
    }
}
