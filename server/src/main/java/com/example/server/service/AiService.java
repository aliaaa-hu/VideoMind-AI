package com.example.server.service;

import com.example.server.dto.AgentFeedback;
import com.example.server.dto.AgentState;
import com.example.server.dto.AnalysisMode;
import com.example.server.dto.TaskStatus;
import com.example.server.dto.TaskStage;
import com.example.server.dto.VideoContext;
import com.example.server.dto.VideoEvidenceHit;
import com.example.server.entity.MediaFile;
import com.example.server.mapper.MediaFileMapper;
import com.example.server.service.mode.ModeRegistry;
import com.example.server.utils.AnalysisTaskKeys;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Service
public class AiService {

    private static final Logger log = LoggerFactory.getLogger(AiService.class);
    /**
     */
    private static final long CONTEXT_LOCK_WAIT_SECONDS = 300;
    private static final Duration CONTEXT_OWNER_TTL = Duration.ofDays(7);

    private final MediaFileMapper mediaFileMapper;
    private final VideoContextService videoContextService;
    private final LongVideoContextService longVideoContextService;
    private final AgentLoopService agentLoopService;
    private final AgentCheckpointService checkpointService;
    private final AgentTelemetry telemetry;
    private final MediaService mediaService;
    private final TaskEventService taskEventService;
    private final RedissonClient redissonClient;
    private final StringRedisTemplate redisTemplate;
    private final ModeRegistry modeRegistry;

    public AiService(MediaFileMapper mediaFileMapper,
                     VideoContextService videoContextService,
                     LongVideoContextService longVideoContextService,
                     AgentLoopService agentLoopService,
                     AgentCheckpointService checkpointService,
                     AgentTelemetry telemetry,
                     MediaService mediaService,
                     TaskEventService taskEventService,
                     RedissonClient redissonClient,
                     StringRedisTemplate redisTemplate,
                     ModeRegistry modeRegistry) {
        this.mediaFileMapper = mediaFileMapper;
        this.videoContextService = videoContextService;
        this.longVideoContextService = longVideoContextService;
        this.agentLoopService = agentLoopService;
        this.checkpointService = checkpointService;
        this.telemetry = telemetry;
        this.mediaService = mediaService;
        this.taskEventService = taskEventService;
        this.redissonClient = redissonClient;
        this.redisTemplate = redisTemplate;
        this.modeRegistry = modeRegistry;
    }

    public void asyncAnalyze(Long mediaId, String userGoal) {
        asyncAnalyze(mediaId, userGoal, AnalysisMode.GENERAL);
    }

    public void asyncAnalyze(Long mediaId, String userGoal, AnalysisMode mode) {
        AnalysisMode resolvedMode = mode == null ? AnalysisMode.GENERAL : mode;
        String traceId = telemetry.start(mediaId, userGoal, resolvedMode);
        telemetry.bind(traceId);
        TaskStage currentStage = TaskStage.VIDEO_CONTEXT;
        MediaFile mediaFile = mediaFileMapper.selectById(mediaId);
        if (mediaFile == null) {
            telemetry.flush(traceId);
            telemetry.clear();
            throw new IllegalArgumentException("media does not exist: " + mediaId);
        }

        try {
            AgentState agentState = checkpointService.loadResult(mediaId, userGoal, resolvedMode);
            if (agentState != null && agentState.result() != null) {
                persistResult(mediaFile, agentState);
                telemetry.increment(traceId, "checkpointHits", 1);
                return;
            }

            VideoContext videoContext = resolveContext(mediaFile, userGoal, traceId, resolvedMode);
            mediaFile.setTranscriptText(videoContext.transcriptText());
            currentStage = TaskStage.AGENT_LOOP;
            taskEventService.publishAnalysis(mediaId, userGoal, resolvedMode,
                    TaskStatus.of(TaskStatus.State.PROCESSING, "Multimodal context is ready. Agent analysis is starting."),
                    TaskStage.AGENT_LOOP);
            long agentStarted = System.nanoTime();
            try {
                agentState = agentLoopService.run(mediaId, videoContext, modeRegistry.of(resolvedMode));
                telemetry.stage(traceId, TaskStage.AGENT_LOOP.name(), agentStarted, true);
            } catch (RuntimeException e) {
                telemetry.stage(traceId, TaskStage.AGENT_LOOP.name(), agentStarted, false);
                throw e;
            }
            persistResult(mediaFile, agentState);
            log.info("agent_analysis_completed traceId={} mediaId={} rounds={}",
                    traceId, mediaId, agentState.round());
        } catch (Exception e) {
            try {
                checkpointService.saveFailure(mediaId, userGoal, resolvedMode, currentStage, e);
            } catch (RuntimeException checkpointError) {
                e.addSuppressed(checkpointError);
                log.error("agent_failure_checkpoint_write_failed traceId={} mediaId={}",
                        traceId, mediaId, checkpointError);
            }
            log.error("agent_analysis_failed traceId={} mediaId={}", traceId, mediaId, e);
            if (e instanceof AgentLoopService.BudgetExceededException budgetExceeded) {
                throw budgetExceeded;
            }
            throw new IllegalStateException("AI analysis failed", e);
        } finally {
            telemetry.flush(traceId);
            telemetry.clear();
        }
    }

    /**
     *
     */
    private VideoContext resolveContext(MediaFile mediaFile,
                                        String userGoal,
                                        String traceId,
                                        AnalysisMode mode) {
        VideoContext checkpoint = checkpointService.loadContext(mediaFile.getId());
        if (checkpoint != null) {
            telemetry.increment(traceId, "contextCheckpointHits", 1);
            return new VideoContext(checkpoint.source(), userGoal, checkpoint.segments());
        }

        String contentHash = AnalysisTaskKeys.normalizeContentHash(
                mediaFile.getId(), mediaService.contentHash(mediaFile.getId()));
        VideoContext reused = reuseContentContext(mediaFile, userGoal, traceId, contentHash);
        if (reused != null) return reused;

        RLock contextLock = redissonClient.getLock(AnalysisTaskKeys.contextLock(contentHash));
        boolean locked = false;
        try {
            locked = contextLock.tryLock(CONTEXT_LOCK_WAIT_SECONDS, TimeUnit.SECONDS);
            VideoContext own = checkpointService.loadContext(mediaFile.getId());
            if (own != null) {
                telemetry.increment(traceId, "contextCheckpointHits", 1);
                return new VideoContext(own.source(), userGoal, own.segments());
            }
            VideoContext afterWait = reuseContentContext(mediaFile, userGoal, traceId, contentHash);
            if (afterWait != null) return afterWait;

            if (!locked) {
                telemetry.increment(traceId, "contextLockContentions", 1);
                log.warn("context_build_in_progress mediaId={} contentHash={} waitedSeconds={}",
                        mediaFile.getId(), contentHash, CONTEXT_LOCK_WAIT_SECONDS);
                throw new IllegalStateException("Video context is already being built. Please try again later.");
            }
            return buildContext(mediaFile, userGoal, traceId, contentHash, mode);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for the video-context build lock", e);
        } finally {
            if (locked && contextLock.isHeldByCurrentThread()) contextLock.unlock();
        }
    }

    private VideoContext reuseContentContext(MediaFile mediaFile,
                                             String userGoal,
                                             String traceId,
                                             String contentHash) {
        Long ownerMediaId = contextOwner(contentHash);
        if (ownerMediaId == null || ownerMediaId.equals(mediaFile.getId())) return null;

        VideoContext ownerContext = checkpointService.loadContext(ownerMediaId);
        if (ownerContext == null) {
            redisTemplate.delete(AnalysisTaskKeys.contextOwner(contentHash));
            return null;
        }

        VideoContext localized = reusableContext(mediaFile.getFilePath(), ownerContext);
        checkpointService.saveContext(mediaFile.getId(), localized);
        telemetry.increment(traceId, "contextContentReuses", 1);
        log.info("video_context_reused mediaId={} sourceMediaId={} contentHash={}",
                mediaFile.getId(), ownerMediaId, contentHash);
        return new VideoContext(localized.source(), userGoal, localized.segments());
    }

    private VideoContext buildContext(MediaFile mediaFile,
                                      String userGoal,
                                      String traceId,
                                      String contentHash,
                                      AnalysisMode mode) {
        taskEventService.publishAnalysis(mediaFile.getId(), userGoal, mode,
                TaskStatus.of(TaskStatus.State.PROCESSING, "Extracting speech and key frames in parallel."),
                TaskStage.VIDEO_CONTEXT);
        long started = System.nanoTime();
        try {
            VideoContext context = videoContextService.build(mediaFile.getFilePath(), userGoal, traceId);
            try {
                checkpointService.saveContext(mediaFile.getId(), context);
            } catch (RuntimeException e) {
                videoContextService.deleteEvidenceFrames(context);
                throw e;
            }
            rememberContextOwner(contentHash, mediaFile.getId());
            telemetry.stage(traceId, TaskStage.VIDEO_CONTEXT.name(), started, true);
            return context;
        } catch (RuntimeException e) {
            telemetry.stage(traceId, TaskStage.VIDEO_CONTEXT.name(), started, false);
            throw e;
        }
    }

    private Long contextOwner(String contentHash) {
        try {
            String value = redisTemplate.opsForValue().get(AnalysisTaskKeys.contextOwner(contentHash));
            return value == null ? null : Long.valueOf(value);
        } catch (NumberFormatException e) {
            redisTemplate.delete(AnalysisTaskKeys.contextOwner(contentHash));
            return null;
        } catch (RuntimeException e) {
            log.warn("context_owner_read_failed contentHash={}", contentHash, e);
            return null;
        }
    }

    private void rememberContextOwner(String contentHash, Long mediaId) {
        try {
            redisTemplate.opsForValue().set(
                    AnalysisTaskKeys.contextOwner(contentHash),
                    String.valueOf(mediaId),
                    CONTEXT_OWNER_TTL);
        } catch (RuntimeException e) {
            log.warn("context_owner_write_failed contentHash={} mediaId={}", contentHash, mediaId, e);
        }
    }

    public String followUp(Long mediaId, String originalGoal, String question) {
        return followUp(mediaId, originalGoal, question, AnalysisMode.GENERAL);
    }

    public String followUp(Long mediaId,
                           String originalGoal,
                           String question,
                           AnalysisMode mode) {
        AnalysisMode resolvedMode = mode == null ? AnalysisMode.GENERAL : mode;
        VideoContext context = checkpointService.loadContext(mediaId);
        if (context == null) throw new VideoContextNotReadyException();

        String traceId = telemetry.start(mediaId, question, resolvedMode);
        telemetry.bind(traceId);
        try {
            AgentState previous = originalGoal == null
                    ? null : checkpointService.loadResult(mediaId, originalGoal, resolvedMode);
            String followUpGoal = contextualQuestion(originalGoal, previous, question);
            VideoContext followUpContext = new VideoContext(
                    context.source(), followUpGoal, context.segments());
            return agentLoopService.run(
                    mediaId, followUpContext, modeRegistry.of(resolvedMode)).result().toMarkdown();
        } finally {
            telemetry.flush(traceId);
            telemetry.clear();
        }
    }

    public List<VideoEvidenceHit> searchEvidence(Long mediaId, String query) {
        VideoContext context = checkpointService.loadContext(mediaId);
        if (context == null) throw new VideoContextNotReadyException();

        String traceId = telemetry.start(mediaId, query);
        telemetry.bind(traceId);
        long started = System.nanoTime();
        try {
            VideoContext searchContext = new VideoContext(
                    context.source(), query, context.segments());
            List<VideoEvidenceHit> hits =
                    longVideoContextService.searchEvidence(mediaId, searchContext);
            telemetry.stage(traceId, TaskStage.RETRIEVAL.name(), started, true);
            return hits;
        } catch (RuntimeException e) {
            telemetry.stage(traceId, TaskStage.RETRIEVAL.name(), started, false);
            throw e;
        } finally {
            telemetry.flush(traceId);
            telemetry.clear();
        }
    }

    public void stageRevision(AgentFeedback feedback) {
        stageRevision(feedback, AnalysisMode.GENERAL);
    }

    public void stageRevision(AgentFeedback feedback, AnalysisMode mode) {
        AnalysisMode resolvedMode = mode == null ? AnalysisMode.GENERAL : mode;
        AgentFeedback normalized = feedback.normalized(resolvedMode);
        checkpointService.saveFeedback(normalized);

        String goal = normalized.correctedGoal() == null || normalized.correctedGoal().isBlank()
                ? normalized.goal()
                : normalized.correctedGoal().trim();
        AgentState.AgentPlan correctedPlan = normalized.correctedTasks().isEmpty()
                ? null
                : new AgentState.AgentPlan(goal, normalized.correctedTasks());
        checkpointService.stageRevision(normalized.mediaId(), goal, resolvedMode, correctedPlan);
    }

    public String revisionGoal(AgentFeedback feedback) {
        AgentFeedback normalized = feedback.normalized();
        return normalized.correctedGoal() == null || normalized.correctedGoal().isBlank()
                ? normalized.goal()
                : normalized.correctedGoal();
    }

    public void cancelStagedRevision(Long mediaId, String goal) {
        cancelStagedRevision(mediaId, goal, AnalysisMode.GENERAL);
    }

    public void cancelStagedRevision(Long mediaId, String goal, AnalysisMode mode) {
        checkpointService.cancelStagedRevision(mediaId, goal, mode);
    }

    public boolean reuseResult(Long mediaId, Long sourceMediaId, AgentState state) {
        return reuseResult(mediaId, sourceMediaId, state, AnalysisMode.GENERAL);
    }

    public boolean reuseResult(Long mediaId, Long sourceMediaId, AgentState state, AnalysisMode mode) {
        MediaFile mediaFile = mediaFileMapper.selectById(mediaId);
        if (mediaFile == null) throw new IllegalArgumentException("media does not exist: " + mediaId);

        VideoContext sourceContext = checkpointService.loadContext(sourceMediaId);
        if (sourceContext == null) return false;
        checkpointService.saveContext(mediaId, reusableContext(mediaFile.getFilePath(), sourceContext));
        checkpointService.saveResult(mediaId, new AgentState(
                state.goal(), state.plan(), state.result(), state.critique(), state.round()), mode);
        persistResult(mediaFile, state);
        return true;
    }

    private VideoContext reusableContext(String targetSource, VideoContext sourceContext) {
        return new VideoContext(targetSource, "", sourceContext.segments().stream()
                .map(segment -> new VideoContext.VideoSegment(
                        segment.startMs(),
                        segment.endMs(),
                        segment.transcript(),
                        segment.ocrTexts(),
                        segment.evidenceFrames().isEmpty()
                                ? java.util.List.of()
                                : java.util.List.of(targetSource + "#timestampMs=" + segment.startMs())))
                .toList());
    }

    private String contextualQuestion(String originalGoal, AgentState previous, String question) {
        if (originalGoal == null || previous == null || previous.result() == null) return question;
        String previousResult = previous.result().toMarkdown();
        if (previousResult.length() > 4_000) previousResult = previousResult.substring(0, 4_000);
        return """
                This is a follow-up question about the same video. Answer it using original video evidence and the existing analysis.
                Original goal: %s
                Existing analysis: %s
                Follow-up question: %s
                """.formatted(originalGoal, previousResult, question);
    }

    private void persistResult(MediaFile mediaFile, AgentState agentState) {
        if (agentState.result() == null) throw new IllegalStateException("Agent did not produce an analysis result");
        mediaFile.setAiSummary(agentState.result().toMarkdown());
        mediaFileMapper.updateById(mediaFile);
        mediaService.invalidateUserList(mediaFile.getUserId());
    }
}
