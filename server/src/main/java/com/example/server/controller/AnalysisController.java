package com.example.server.controller;

import com.example.server.common.ErrorCode;
import com.example.server.common.Result;
import com.example.server.dto.AgentFeedback;
import com.example.server.dto.AgentState;
import com.example.server.dto.AnalysisMode;
import com.example.server.dto.RouteDecision;
import com.example.server.dto.RouteRequest;
import com.example.server.dto.TaskStatus;
import com.example.server.dto.VideoEvidenceHit;
import com.example.server.entity.MediaFile;
import com.example.server.exception.BusinessException;
import com.example.server.service.AgentCheckpointService;
import com.example.server.service.AnalysisDispatchService;
import com.example.server.service.AnalysisStatusService;
import com.example.server.service.AgentEvaluationService;
import com.example.server.service.AgentTelemetry;
import com.example.server.service.AiService;
import com.example.server.service.AuthService;
import com.example.server.service.MediaService;
import com.example.server.service.TaskEventService;
import com.example.server.service.VideoContextNotReadyException;
import com.example.server.service.mode.ModeRouter;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Supplier;

@RestController
@RequestMapping("/analysis")
public class AnalysisController {

    private static final int MAX_GOAL_LENGTH = 500;

    private final AiService aiService;
    private final AnalysisDispatchService dispatchService;
    private final AgentCheckpointService checkpointService;
    private final AgentEvaluationService evaluationService;
    private final AgentTelemetry telemetry;
    private final MediaService mediaService;
    private final TaskEventService taskEventService;
    private final AnalysisStatusService statusService;
    private final ModeRouter modeRouter;
    private final Executor aiTaskExecutor;

    public AnalysisController(AiService aiService,
                              AnalysisDispatchService dispatchService,
                              AgentCheckpointService checkpointService,
                              AgentEvaluationService evaluationService,
                              AgentTelemetry telemetry,
                              MediaService mediaService,
                              TaskEventService taskEventService,
                              AnalysisStatusService statusService,
                              ModeRouter modeRouter,
                              @Qualifier("aiTaskExecutor") Executor aiTaskExecutor) {
        this.aiService = aiService;
        this.dispatchService = dispatchService;
        this.checkpointService = checkpointService;
        this.evaluationService = evaluationService;
        this.telemetry = telemetry;
        this.mediaService = mediaService;
        this.taskEventService = taskEventService;
        this.statusService = statusService;
        this.modeRouter = modeRouter;
        this.aiTaskExecutor = aiTaskExecutor;
    }

    /**
     *
     */
    @PostMapping("/route")
    public Result<RouteDecision> route(
            @Valid @RequestBody RouteRequest request,
            @RequestAttribute(AuthService.REQUEST_USER_ID) Long userId) {
        return Result.ok(modeRouter.route(
                normalizeText(request.goal(), "Analysis goal"), userId));
    }

    @PostMapping("/ai")
    public ResponseEntity<Result<Void>> aiAnalyze(
            @RequestParam Long id,
            @RequestParam(defaultValue = "Understand the video and produce a structured analysis report") String goal,
            @RequestParam(required = false) String mode,
            @RequestAttribute(AuthService.REQUEST_USER_ID) Long userId) {
        String normalizedGoal = normalizeText(goal, "Analysis goal");
        AnalysisMode analysisMode = AnalysisMode.fromRequest(mode);
        MediaFile mediaFile = mediaService.requireOwnedMedia(id, userId);
        if (checkpointService.loadResult(id, normalizedGoal, analysisMode) != null) {
            return ResponseEntity.ok(Result.ok());
        }
        return submissionResponse(dispatchService.submit(mediaFile, normalizedGoal, null, analysisMode));
    }

    @PostMapping("/follow-up")
    public CompletableFuture<Result<String>> followUp(
            @RequestParam Long id,
            @RequestParam String question,
            @RequestParam(required = false) String goal,
            @RequestParam(required = false) String mode,
            @RequestAttribute(AuthService.REQUEST_USER_ID) Long userId) {
        String normalizedQuestion = normalizeText(question, "Follow-up question");
        String normalizedGoal = goal == null || goal.isBlank()
                ? null : normalizeText(goal, "Original analysis goal");
        mediaService.requireOwnedMedia(id, userId);
        requireVideoContext(id);
        dispatchService.requireAiQuota(userId);
        AnalysisMode analysisMode = AnalysisMode.fromRequest(mode);
        return runInteractive(() -> aiService.followUp(
                id, normalizedGoal, normalizedQuestion, analysisMode));
    }

    @GetMapping("/evidence-search")
    public CompletableFuture<Result<List<VideoEvidenceHit>>> searchEvidence(
            @RequestParam Long id,
            @RequestParam String query,
            @RequestAttribute(AuthService.REQUEST_USER_ID) Long userId) {
        mediaService.requireOwnedMedia(id, userId);
        requireVideoContext(id);
        dispatchService.requireAiQuota(userId);
        String normalizedQuery = normalizeText(query, "Search query");
        return runInteractive(() -> aiService.searchEvidence(id, normalizedQuery));
    }

    @PostMapping("/agent-feedback")
    public Result<Void> agentFeedback(
            @Valid @RequestBody AgentFeedback feedback,
            @RequestAttribute(AuthService.REQUEST_USER_ID) Long userId) {
        ensureRating(feedback);
        mediaService.requireOwnedMedia(feedback.mediaId(), userId);
        checkpointService.saveFeedback(
                feedback.normalized(AnalysisMode.fromRequest(feedback.mode())));
        return Result.ok();
    }

    @PostMapping("/agent-revise")
    public ResponseEntity<Result<Void>> reviseAgentResult(
            @Valid @RequestBody AgentFeedback feedback,
            @RequestParam(required = false) String mode,
            @RequestAttribute(AuthService.REQUEST_USER_ID) Long userId) {
        ensureRating(feedback);
        MediaFile mediaFile = mediaService.requireOwnedMedia(feedback.mediaId(), userId);
        String revisedGoal = aiService.revisionGoal(feedback);
        return submissionResponse(
                dispatchService.submit(mediaFile, revisedGoal, feedback, AnalysisMode.fromRequest(mode)));
    }

    @GetMapping("/agent-feedback")
    public Result<List<AgentFeedback>> agentFeedback(
            @RequestParam Long id,
            @RequestAttribute(AuthService.REQUEST_USER_ID) Long userId) {
        mediaService.requireOwnedMedia(id, userId);
        return Result.ok(checkpointService.loadFeedback(id));
    }

    @GetMapping("/agent-plan")
    public Result<AgentState.AgentPlan> agentPlan(
            @RequestParam Long id,
            @RequestParam String goal,
            @RequestParam(required = false) String mode,
            @RequestAttribute(AuthService.REQUEST_USER_ID) Long userId) {
        mediaService.requireOwnedMedia(id, userId);
        return Result.ok(checkpointService.loadPlan(
                id, normalizeText(goal, "Analysis goal"), AnalysisMode.fromRequest(mode)));
    }

    @GetMapping("/analysis-status")
    public Result<TaskStatus> analysisStatus(
            @RequestParam Long id,
            @RequestParam String goal,
            @RequestParam(required = false) String mode,
            @RequestAttribute(AuthService.REQUEST_USER_ID) Long userId) {
        mediaService.requireOwnedMedia(id, userId);
        String normalizedGoal = normalizeText(goal, "Analysis goal");
        return Result.ok(statusService.current(id, normalizedGoal, AnalysisMode.fromRequest(mode)));
    }

    @GetMapping(value = "/analysis-events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter analysisEvents(
            @RequestParam Long id,
            @RequestParam String goal,
            @RequestParam(required = false) String mode,
            @RequestAttribute(AuthService.REQUEST_USER_ID) Long userId) {
        mediaService.requireOwnedMedia(id, userId);
        String normalizedGoal = normalizeText(goal, "Analysis goal");
        AnalysisMode analysisMode = AnalysisMode.fromRequest(mode);
        return taskEventService.subscribe(
                id,
                TaskEventService.ANALYSIS,
                normalizedGoal,
                analysisMode,
                statusService.current(id, normalizedGoal, analysisMode),
                statusService.stage(id, normalizedGoal, analysisMode));
    }

    @GetMapping("/agent-evaluation")
    public Result<Map<String, Object>> agentEvaluation(
            @RequestParam Long id,
            @RequestParam String goal,
            @RequestParam(required = false) String mode,
            @RequestAttribute(AuthService.REQUEST_USER_ID) Long userId) {
        mediaService.requireOwnedMedia(id, userId);
        return Result.ok(evaluationService.evaluate(
                id, normalizeText(goal, "Analysis goal"), AnalysisMode.fromRequest(mode)));
    }

    @GetMapping("/agent-trace")
    public Result<Map<String, Object>> agentTrace(
            @RequestParam Long id,
            @RequestParam String goal,
            @RequestParam(required = false) String mode,
            @RequestAttribute(AuthService.REQUEST_USER_ID) Long userId) {
        mediaService.requireOwnedMedia(id, userId);
        return Result.ok(telemetry.latest(
                id, normalizeText(goal, "Analysis goal"), AnalysisMode.fromRequest(mode)));
    }

    /**
     */
    private String normalizeText(String value, String field) {
        if (value == null || value.isBlank() || value.length() > MAX_GOAL_LENGTH) {
            throw new IllegalArgumentException(field + " is required and must not exceed " + MAX_GOAL_LENGTH + " characters");
        }
        return value.trim();
    }

    private void ensureRating(AgentFeedback feedback) {
        Integer rating = feedback.rating();
        if (rating != null && rating != -1 && rating != 1) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "Rating must be -1 or 1");
        }
    }

    private void requireVideoContext(Long mediaId) {
        if (checkpointService.loadContext(mediaId) == null) {
            throw new VideoContextNotReadyException();
        }
    }

    private <T> CompletableFuture<Result<T>> runInteractive(Supplier<T> action) {
        try {
            return CompletableFuture.supplyAsync(() -> Result.ok(action.get()), aiTaskExecutor);
        } catch (RejectedExecutionException e) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "AI is busy. Please try again later.");
        }
    }

    /**
     */
    private ResponseEntity<Result<Void>> submissionResponse(
            AnalysisDispatchService.SubmissionResult result) {
        return switch (result) {
            case ACCEPTED -> ResponseEntity.accepted().body(Result.ok());
            case RATE_LIMITED -> throw new BusinessException(ErrorCode.RATE_LIMITED, "The system is busy. Please try again later.");
            case DUPLICATE -> throw new BusinessException(ErrorCode.CONFLICT, "An analysis for this video and goal is already running.");
            case FAILED -> throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE,
                    "The analysis queue is unavailable. Please try again shortly.");
        };
    }
}
