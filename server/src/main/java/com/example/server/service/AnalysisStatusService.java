package com.example.server.service;

import com.example.server.dto.AgentState;
import com.example.server.dto.AnalysisMode;
import com.example.server.dto.TaskStage;
import com.example.server.dto.TaskStatus;
import org.springframework.stereotype.Service;

@Service
public class AnalysisStatusService {

    private final AgentCheckpointService checkpointService;
    private final AnalysisDispatchService dispatchService;

    public AnalysisStatusService(AgentCheckpointService checkpointService,
                                 AnalysisDispatchService dispatchService) {
        this.checkpointService = checkpointService;
        this.dispatchService = dispatchService;
    }

    public TaskStatus current(Long mediaId, String goal) {
        return current(mediaId, goal, AnalysisMode.GENERAL);
    }

    public TaskStatus current(Long mediaId, String goal, AnalysisMode mode) {
        AgentState result = checkpointService.loadResult(mediaId, goal, mode);
        if (result != null && result.result() != null) {
            return TaskStatus.completed(result);
        }

        TaskStage stage = checkpointService.loadStage(mediaId, goal, mode);
        if (dispatchService.isActive(mediaId, goal, mode)) {
            TaskStatus.State state = stage == null ? TaskStatus.State.QUEUED : TaskStatus.State.PROCESSING;
            return TaskStatus.of(state, statusMessage(stage));
        }
        if (stage == TaskStage.BUDGET_EXHAUSTED) {
            return TaskStatus.of(TaskStatus.State.FAILED, "The Agent reached its task budget. Adjust the goal and try again.");
        }
        if (stage == TaskStage.FAILED || stage == TaskStage.DEAD_LETTERED) {
            return TaskStatus.of(TaskStatus.State.FAILED, "Analysis failed. Please try again later.");
        }
        return TaskStatus.of(TaskStatus.State.NOT_STARTED, "No analysis task has been submitted.");
    }

    public TaskStage stage(Long mediaId, String goal) {
        return stage(mediaId, goal, AnalysisMode.GENERAL);
    }

    public TaskStage stage(Long mediaId, String goal, AnalysisMode mode) {
        return checkpointService.loadStage(mediaId, goal, mode);
    }

    private String statusMessage(TaskStage stage) {
        if (stage == null || stage == TaskStage.QUEUED) return "Task is queued.";
        return switch (stage) {
            case VIDEO_CONTEXT, CONTEXT_COMPLETED -> "Extracting speech and key frames from the video.";
            case CHUNKS_COMPLETED -> "Retrieving video evidence relevant to the goal.";
            case PLAN_COMPLETED -> "Planner completed task decomposition.";
            case EXECUTOR_STARTED, EXECUTOR_COMPLETED -> "Executor is generating structured output.";
            case CRITIC_STARTED -> "Critic is verifying conclusions and evidence.";
            case CRITIC_RETRY_REQUIRED, EVIDENCE_REFRESHED -> "Adding evidence based on Critic feedback.";
            case RETRYING -> "Task execution failed and is retrying automatically.";
            default -> "Analyzing video.";
        };
    }
}
