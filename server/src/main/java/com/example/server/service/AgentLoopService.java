package com.example.server.service;

import com.example.server.dto.AgentState;
import com.example.server.dto.AnalysisMode;
import com.example.server.dto.AnalysisResult;
import com.example.server.dto.TaskStatus;
import com.example.server.dto.TaskStage;
import com.example.server.dto.VideoContext;
import com.example.server.service.mode.ModeProfile;
import com.example.server.utils.DeepSeekUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class AgentLoopService {

    private static final Logger log = LoggerFactory.getLogger(AgentLoopService.class);
    private static final int MAX_PLAN_TASKS = 5;

    private final DeepSeekUtils deepSeekUtils;
    private final LongVideoContextService longVideoContextService;
    private final AgentCheckpointService checkpointService;
    private final AgentTelemetry telemetry;
    private final EvidenceVerificationService evidenceVerificationService;
    private final TaskEventService taskEventService;
    private final int maxRounds;
    private final long maxDurationMs;
    private final long maxEstimatedTokens;
    private final double maxEstimatedCost;

    public AgentLoopService(DeepSeekUtils deepSeekUtils,
                            LongVideoContextService longVideoContextService,
                            AgentCheckpointService checkpointService,
                            AgentTelemetry telemetry,
                            EvidenceVerificationService evidenceVerificationService,
                            TaskEventService taskEventService,
                            @Value("${agent.budget.max-rounds:2}") int maxRounds,
                            @Value("${agent.budget.max-duration-ms:120000}") long maxDurationMs,
                            @Value("${agent.budget.max-estimated-tokens:50000}") long maxEstimatedTokens,
                            @Value("${agent.budget.max-estimated-cost:0}") double maxEstimatedCost) {
        this.deepSeekUtils = deepSeekUtils;
        this.longVideoContextService = longVideoContextService;
        this.checkpointService = checkpointService;
        this.telemetry = telemetry;
        this.evidenceVerificationService = evidenceVerificationService;
        this.taskEventService = taskEventService;
        if (maxRounds < 1 || maxDurationMs < 1 || maxEstimatedTokens < 1 || maxEstimatedCost < 0) {
            throw new IllegalArgumentException("Agent termination budget configuration is invalid");
        }
        this.maxRounds = maxRounds;
        this.maxDurationMs = maxDurationMs;
        this.maxEstimatedTokens = maxEstimatedTokens;
        this.maxEstimatedCost = maxEstimatedCost;
    }

    public AgentState run(VideoContext context) {
        return run(null, context, null);
    }

    public AgentState run(Long mediaId, VideoContext context) {
        return run(mediaId, context, null);
    }

    /**
     */
    public AgentState run(Long mediaId, VideoContext context, ModeProfile profile) {
        validateContext(context);
        try (AgentExecutionBudget.Scope ignored = AgentExecutionBudget.open(maxDurationMs)) {
            return runWithinBudget(mediaId, context, profile);
        } catch (BudgetExceededException e) {
            throw e;
        } catch (RuntimeException e) {
            AgentExecutionBudget.DeadlineExceededException deadline = findDeadline(e);
            if (deadline == null) throw e;
            telemetry.incrementCurrent("budgetTerminations", 1);
            throw new BudgetExceededException(deadline.getMessage(), e);
        }
    }

    private AgentState runWithinBudget(Long mediaId, VideoContext context, ModeProfile profile) {
        long runStartedNanos = System.nanoTime();
        AgentState savedState = mediaId == null ? null
                : checkpointService.loadCriticState(mediaId, context.userGoal(), modeOf(profile));
        boolean terminalCheckpoint = savedState != null && savedState.result() != null
                && savedState.critique() != null
                && (savedState.round() >= maxRounds || savedState.critique().passed());
        if (terminalCheckpoint
                && isPlanValid(savedState.plan())
                && isResultValid(savedState.result(), profile)) {
            checkpointService.saveResult(mediaId, savedState, modeOf(profile));
            telemetry.incrementCurrent("terminalCheckpointHits", 1);
            return savedState;
        }
        if (terminalCheckpoint) {
            telemetry.incrementCurrent("invalidTerminalCheckpointRepairs", 1);
            savedState = new AgentState(
                    savedState.goal(), savedState.plan(), savedState.result(), savedState.critique(), 0);
        }

        VideoContext relevantContext = longVideoContextService.selectRelevant(mediaId, context);
        AgentState.AgentPlan plan = resolvePlan(mediaId, relevantContext, savedState, profile);
        checkBudget(runStartedNanos, "Planner");
        if (mediaId != null) {
            taskEventService.publishAnalysis(mediaId, relevantContext.userGoal(), modeOf(profile),
                    TaskStatus.of(TaskStatus.State.PROCESSING, "Planner completed task decomposition"),
                    TaskStage.PLAN_COMPLETED);
        }
        AgentState state = savedState == null
                ? new AgentState(relevantContext.userGoal(), plan, null, null, 0)
                : savedState;
        if (state.critique() != null && !state.critique().passed()) {
            relevantContext = contextForRetry(
                    mediaId, context, relevantContext, state.critique(), profile);
            plan = revisePlanForRetry(mediaId, relevantContext, plan, state.critique(), profile);
        }

        if (state.result() != null && state.critique() == null && state.round() > 0) {
            telemetry.incrementCurrent("criticCheckpointResumes", 1);
            checkBudget(runStartedNanos, "Executor Checkpoint");
            state = critiqueRound(mediaId, relevantContext, plan, state.result(), state.round(), profile);
            if (!state.critique().passed() && state.round() < maxRounds) {
                relevantContext = contextForRetry(
                        mediaId, context, relevantContext, state.critique(), profile);
                plan = revisePlanForRetry(mediaId, relevantContext, plan, state.critique(), profile);
            }
        }

        for (int round = state.round() + 1; round <= maxRounds; round++) {
            checkBudget(runStartedNanos, "Agent Round " + round);
            state = executeRound(
                    mediaId, relevantContext, plan, state.critique(), round, runStartedNanos, profile);
            if (state.critique().passed()) break;
            if (round < maxRounds) {
                relevantContext = contextForRetry(
                        mediaId, context, relevantContext, state.critique(), profile);
                plan = revisePlanForRetry(mediaId, relevantContext, plan, state.critique(), profile);
            }
        }
        validateResult(state.result(), profile);
        if (mediaId != null) checkpointService.saveResult(mediaId, state, modeOf(profile));
        return state;
    }

    private AgentState.AgentPlan resolvePlan(Long mediaId,
                                             VideoContext context,
                                             AgentState savedState,
                                             ModeProfile profile) {
        AgentState.AgentPlan plan = mediaId == null
                ? null
                : checkpointService.loadPlan(mediaId, context.userGoal(), modeOf(profile));
        if (plan == null && savedState != null) plan = savedState.plan();
        boolean shouldPersist = false;
        if (plan == null) {
            plan = deepSeekUtils.plan(context, planInstruction(profile));
            shouldPersist = true;
        }
        if (!isPlanValid(plan)) {
            plan = deepSeekUtils.repairPlan(context, plan, planInstruction(profile));
            telemetry.incrementCurrent("planStructureRepairs", 1);
            shouldPersist = true;
        }
        validatePlan(plan);
        if (mediaId != null && shouldPersist) {
            checkpointService.savePlan(mediaId, context.userGoal(), modeOf(profile), plan);
        }
        return plan;
    }

    private AgentState executeRound(Long mediaId,
                                    VideoContext context,
                                    AgentState.AgentPlan plan,
                                    AgentState.CriticResult previousCritique,
                                    int round,
                                    long runStartedNanos,
                                    ModeProfile profile) {
        publishStage(mediaId, context.userGoal(), modeOf(profile),
                "Executor is generating a structured deliverable from the plan", TaskStage.EXECUTOR_STARTED);
        AnalysisResult result = deepSeekUtils.execute(context, plan, previousCritique, executeInstruction(profile));
        AgentState draft = new AgentState(context.userGoal(), plan, result, null, round);
        if (mediaId != null) {
            checkpointService.saveExecutionState(mediaId, draft, modeOf(profile));
            publishStage(mediaId, context.userGoal(), modeOf(profile),
                    "Executor draft saved; starting evidence verification", TaskStage.EXECUTOR_COMPLETED);
        }
        checkBudget(runStartedNanos, "Executor");
        return critiqueRound(mediaId, context, plan, result, round, profile);
    }

    private AgentState critiqueRound(Long mediaId,
                                     VideoContext context,
                                     AgentState.AgentPlan plan,
                                     AnalysisResult result,
                                     int round,
                                     ModeProfile profile) {
        publishStage(mediaId, context.userGoal(), modeOf(profile),
                "Critic is checking goal coverage and timestamp evidence", TaskStage.CRITIC_STARTED);
        AgentState.CriticResult critique = normalizeCritique(
                deepSeekUtils.critique(context, plan, result, criticInstruction(profile)));
        critique = enforceStructureBounds(result, critique, profile);
        critique = enforceEvidenceBounds(context, result, critique);
        telemetry.incrementCurrent("criticRounds", 1);
        if (critique.passed()) telemetry.incrementCurrent("criticPassed", 1);

        AgentState state = new AgentState(context.userGoal(), plan, result, critique, round);
        if (mediaId != null) {
            checkpointService.saveCriticState(mediaId, state, modeOf(profile));
            String message;
            TaskStage stage;
            if (critique.passed()) {
                message = "Critic validation passed; assembling the structured result";
                stage = TaskStage.CRITIC_PASSED;
            } else if (round >= maxRounds) {
                message = "Critic reached the maximum validation rounds; preserving warnings and generating the result";
                stage = TaskStage.ANALYSIS_COMPLETED_WITH_WARNINGS;
            } else if (requiresEvidenceRefresh(critique)) {
                message = "Critic found evidence gaps; retrieving targeted additional evidence";
                stage = TaskStage.CRITIC_RETRY_REQUIRED;
            } else {
                message = "Critic found goal-coverage or structure issues; rewriting from feedback";
                stage = TaskStage.CRITIC_RETRY_REQUIRED;
            }
            publishStage(mediaId, context.userGoal(), modeOf(profile), message, stage);
        }
        return state;
    }

    private void validateContext(VideoContext context) {
        if (context == null || context.userGoal() == null || context.userGoal().isBlank()
                || context.segments() == null || context.segments().isEmpty()
                || context.segments().stream().anyMatch(java.util.Objects::isNull)) {
            throw new IllegalArgumentException("Agent requires a goal and at least one video segment");
        }
    }

    private void validatePlan(AgentState.AgentPlan plan) {
        if (!isPlanValid(plan)) {
            throw new IllegalStateException("Planner returned an invalid task list");
        }
    }

    private boolean isPlanValid(AgentState.AgentPlan plan) {
        return plan != null && plan.understoodGoal() != null && !plan.understoodGoal().isBlank()
                && plan.tasks() != null && !plan.tasks().isEmpty()
                && plan.tasks().size() <= MAX_PLAN_TASKS
                && plan.tasks().stream().noneMatch(
                task -> task == null || task.isBlank() || task.length() > 500);
    }

    private void validateResult(AnalysisResult result, ModeProfile profile) {
        if (!isResultValid(result, profile)) {
            throw new IllegalStateException("Executor did not produce a complete structured result");
        }
    }

    private boolean isResultValid(AnalysisResult result, ModeProfile profile) {
        boolean commonFieldsValid = result != null
                && result.title() != null && !result.title().isBlank()
                && result.conclusions() != null && !result.conclusions().isEmpty()
                && result.evidence() != null && !result.evidence().isEmpty();
        if (!commonFieldsValid || profile == null || profile.requiredSectionKeys().isEmpty()) {
            return commonFieldsValid;
        }
        List<String> presentKeys = result.sections().stream()
                .filter(section -> section != null && section.key() != null
                        && !section.key().isBlank() && !section.items().isEmpty())
                .map(section -> section.key().trim())
                .distinct()
                .toList();
        return presentKeys.containsAll(profile.requiredSectionKeys());
    }

    private AgentState.CriticResult enforceEvidenceBounds(VideoContext context,
                                                           AnalysisResult result,
                                                           AgentState.CriticResult critique) {
        critique = normalizeCritique(critique);
        boolean hasDeclaredProblems = !critique.feedback().isEmpty()
                || !critique.missingRequirements().isEmpty()
                || !critique.unsupportedClaims().isEmpty()
                || !critique.requiredTimestamps().isEmpty();
        if (critique.passed() && hasDeclaredProblems) {
            critique = new AgentState.CriticResult(
                    false,
                    critique.feedback(),
                    critique.missingRequirements(),
                    critique.unsupportedClaims(),
                    critique.requiredTimestamps());
        }
        if (!critique.passed()
                && critique.feedback().isEmpty()
                && critique.missingRequirements().isEmpty()
                && critique.unsupportedClaims().isEmpty()
                && critique.requiredTimestamps().isEmpty()) {
            critique = new AgentState.CriticResult(
                    false,
                    List.of("Recheck goal coverage, structural completeness, and evidence binding"),
                    List.of(), List.of(), List.of());
        }
        if (result == null || result.evidence() == null || result.evidence().isEmpty()) return critique;
        List<AnalysisResult.Evidence> invalidEvidence = result.evidence().stream()
                .filter(evidence -> !evidenceVerificationService.supported(context, evidence))
                .toList();
        List<String> unsupportedClaims = result.conclusions().stream()
                .filter(claim -> result.evidence().stream().noneMatch(
                        evidence -> evidenceVerificationService.supportsClaim(context, claim, evidence)))
                .toList();
        if (invalidEvidence.isEmpty() && unsupportedClaims.isEmpty()) return critique;

        List<String> unsupported = new ArrayList<>(critique.unsupportedClaims());
        unsupportedClaims.stream()
                .filter(claim -> !unsupported.contains(claim))
                .forEach(unsupported::add);
        invalidEvidence.stream()
                .map(evidence -> "Evidence cannot be verified in original ASR/OCR: " + evidence.timestampMs())
                .forEach(unsupported::add);
        List<String> feedback = new ArrayList<>(critique.feedback());
        feedback.add("Retrieve and bind verifiable timestamp evidence for each conclusion");
        List<Long> requiredTimestamps = new ArrayList<>(critique.requiredTimestamps());
        invalidEvidence.stream()
                .map(AnalysisResult.Evidence::timestampMs)
                .filter(timestamp -> !requiredTimestamps.contains(timestamp))
                .forEach(requiredTimestamps::add);
        return new AgentState.CriticResult(
                false,
                feedback,
                critique.missingRequirements(),
                unsupported,
                requiredTimestamps);
    }

    private AgentState.CriticResult enforceStructureBounds(AnalysisResult result,
                                                            AgentState.CriticResult critique,
                                                            ModeProfile profile) {
        critique = normalizeCritique(critique);
        List<String> feedback = new ArrayList<>(critique.feedback());
        if (result == null || result.title() == null || result.title().isBlank()) {
            feedback.add("Add a clear deliverable title");
        }
        if (result == null || result.conclusions() == null || result.conclusions().isEmpty()) {
            feedback.add("Add core conclusions that cover Planner tasks");
        }
        if (result == null || result.evidence() == null || result.evidence().isEmpty()) {
            feedback.add("Add timestamped ASR or OCR evidence for core conclusions");
        }
        List<String> missingSections = missingSectionKeys(result, profile);
        if (!missingSections.isEmpty()) {
            feedback.add("Add structured sections required by the current analysis mode: " + String.join(", ", missingSections));
        }
        if (feedback.equals(critique.feedback())) return critique;
        return new AgentState.CriticResult(
                false,
                feedback,
                critique.missingRequirements(),
                critique.unsupportedClaims(),
                critique.requiredTimestamps());
    }

    private VideoContext contextForRetry(Long mediaId,
                                         VideoContext fullContext,
                                         VideoContext selectedContext,
                                         AgentState.CriticResult critique,
                                         ModeProfile profile) {
        if (!requiresEvidenceRefresh(critique)) {
            telemetry.incrementCurrent("criticRewriteOnlyRetries", 1);
            return selectedContext;
        }
        telemetry.incrementCurrent("criticEvidenceRefreshes", 1);
        VideoContext refined = longVideoContextService.refineForCritique(
                mediaId, fullContext, selectedContext, critique);
        publishStage(mediaId, fullContext.userGoal(), modeOf(profile),
                "Targeted evidence supplemented from Critic feedback", TaskStage.EVIDENCE_REFRESHED);
        return refined;
    }

    private boolean requiresEvidenceRefresh(AgentState.CriticResult critique) {
        return critique != null
                && (!safeList(critique.requiredTimestamps()).isEmpty()
                || !safeList(critique.missingRequirements()).isEmpty()
                || !safeList(critique.unsupportedClaims()).isEmpty());
    }

    private AgentState.AgentPlan revisePlanForRetry(Long mediaId,
                                                    VideoContext context,
                                                    AgentState.AgentPlan currentPlan,
                                                    AgentState.CriticResult critique,
                                                    ModeProfile profile) {
        if (critique == null || safeList(critique.missingRequirements()).isEmpty()) return currentPlan;

        try {
            AgentState.AgentPlan revisedPlan = deepSeekUtils.replan(
                    context, currentPlan, critique, planInstruction(profile));
            validatePlan(revisedPlan);
            telemetry.incrementCurrent("planRevisions", 1);
            if (mediaId != null) {
                checkpointService.savePlan(mediaId, context.userGoal(), modeOf(profile), revisedPlan);
                taskEventService.publishAnalysis(mediaId, context.userGoal(), modeOf(profile),
                        TaskStatus.of(TaskStatus.State.PROCESSING, "Planner added missing tasks from Critic feedback"),
                        TaskStage.PLAN_COMPLETED);
            }
            return revisedPlan;
        } catch (RuntimeException e) {
            telemetry.incrementCurrent("planRevisionFallbacks", 1);
            log.warn("agent_replan_failed mediaId={}, fallback to current plan", mediaId, e);
            return currentPlan;
        }
    }

    private void publishStage(Long mediaId,
                              String goal,
                              AnalysisMode mode,
                              String message,
                              TaskStage stage) {
        if (mediaId == null) return;
        taskEventService.publishAnalysis(mediaId, goal, mode,
                TaskStatus.of(TaskStatus.State.PROCESSING, message), stage);
    }

    private List<String> missingSectionKeys(AnalysisResult result, ModeProfile profile) {
        if (profile == null || profile.requiredSectionKeys().isEmpty()) return List.of();
        List<String> presentKeys = result == null || result.sections() == null
                ? List.of()
                : result.sections().stream()
                        .filter(section -> section != null
                                && section.key() != null
                                && !section.key().isBlank()
                                && section.items() != null
                                && !section.items().isEmpty())
                        .map(section -> section.key().trim())
                        .distinct()
                        .toList();
        return profile.requiredSectionKeys().stream()
                .filter(required -> !presentKeys.contains(required))
                .toList();
    }

    private void checkBudget(long startedNanos, String completedStage) {
        AgentExecutionBudget.check(completedStage);
        long elapsedMs = (System.nanoTime() - startedNanos) / 1_000_000;
        AgentTelemetry.BudgetUsage usage = telemetry.currentUsage();
        String reason = null;
        if (elapsedMs > maxDurationMs) {
            reason = "Agent exceeded the maximum execution duration of " + maxDurationMs + "ms";
        } else if (usage.estimatedTokens() > maxEstimatedTokens) {
            reason = "Agent exceeded the maximum token budget of " + maxEstimatedTokens;
        } else if (maxEstimatedCost > 0 && usage.estimatedCost() > maxEstimatedCost) {
            reason = "Agent exceeded the maximum cost budget of " + maxEstimatedCost;
        }
        if (reason == null) return;
        telemetry.incrementCurrent("budgetTerminations", 1);
        throw new BudgetExceededException("Stopped after " + completedStage + ": " + reason);
    }

    public static class BudgetExceededException extends IllegalStateException {
        public BudgetExceededException(String message) {
            super(message);
        }

        public BudgetExceededException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private AgentExecutionBudget.DeadlineExceededException findDeadline(Throwable error) {
        Throwable current = error;
        for (int depth = 0; current != null && depth < 16; depth++) {
            if (current instanceof AgentExecutionBudget.DeadlineExceededException deadline) {
                return deadline;
            }
            if (current.getCause() == current) break;
            current = current.getCause();
        }
        return null;
    }

    private AgentState.CriticResult normalizeCritique(AgentState.CriticResult critique) {
        if (critique == null) {
            return new AgentState.CriticResult(
                    false, List.of("Critic did not return a valid result"),
                    List.of(), List.of(), List.of());
        }
        return new AgentState.CriticResult(
                critique.passed(),
                safeList(critique.feedback()),
                safeList(critique.missingRequirements()),
                safeList(critique.unsupportedClaims()),
                safeList(critique.requiredTimestamps()));
    }

    private <T> List<T> safeList(List<T> values) {
        return values == null ? List.of() : values;
    }

    private static AnalysisMode modeOf(ModeProfile profile) {
        return profile == null ? AnalysisMode.GENERAL : profile.mode();
    }

    private static String planInstruction(ModeProfile profile) {
        return profile == null ? "" : profile.planInstruction();
    }

    private static String executeInstruction(ModeProfile profile) {
        return profile == null ? "" : profile.executeInstruction();
    }

    private static String criticInstruction(ModeProfile profile) {
        return profile == null ? "" : profile.criticInstruction();
    }
}
