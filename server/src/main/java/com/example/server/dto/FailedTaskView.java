package com.example.server.dto;

import com.example.server.entity.FailedAnalysisTask;

import java.time.LocalDateTime;

/**
 *
 */
public record FailedTaskView(
        Long id,
        Long mediaId,
        String action,
        String mode,
        String userGoal,
        Integer attemptCount,
        String errorType,
        String errorMessage,
        String status,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    public static FailedTaskView from(FailedAnalysisTask task) {
        return new FailedTaskView(
                task.getId(),
                task.getMediaId(),
                task.getAction(),
                task.getMode(),
                task.getUserGoal(),
                task.getAttemptCount(),
                task.getErrorType(),
                task.getErrorMessage(),
                task.getStatus(),
                task.getCreatedAt(),
                task.getUpdatedAt());
    }
}
