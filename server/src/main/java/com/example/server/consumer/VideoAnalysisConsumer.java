package com.example.server.consumer;

import com.example.server.dto.AnalysisMode;
import com.example.server.dto.AnalysisTaskMsg;
import com.example.server.dto.AgentState;
import com.example.server.dto.TaskStatus;
import com.example.server.dto.TaskStage;
import com.example.server.service.AiService;
import com.example.server.service.AgentCheckpointService;
import com.example.server.service.AgentLoopService;
import com.example.server.service.FailedAnalysisTaskService;
import com.example.server.service.MediaService;
import com.example.server.service.TaskEventService;
import com.example.server.utils.AnalysisTaskKeys;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.NoSuchElementException;

@Component
@RocketMQMessageListener(
        topic = "${rocketmq.topic.video-analysis:video-analysis-topic}",
        consumerGroup = "${rocketmq.consumer.group:video-analysis-group}",
        //
        maxReconsumeTimes = 2)
public class VideoAnalysisConsumer implements RocketMQListener<AnalysisTaskMsg> {

    private static final Logger log = LoggerFactory.getLogger(VideoAnalysisConsumer.class);
    private static final int MAX_DELIVERY_ATTEMPTS = 3;
    private static final Duration ACTIVE_TTL = Duration.ofHours(6);
    private static final int MAX_CAUSE_DEPTH = 16;

    private final AiService aiService;
    private final RedissonClient redissonClient;
    private final StringRedisTemplate redisTemplate;
    private final AgentCheckpointService checkpointService;
    private final RocketMQTemplate rocketMQTemplate;
    private final FailedAnalysisTaskService failedTaskService;
    private final MediaService mediaService;
    private final TaskEventService taskEventService;
    private final String deadLetterTopic;

    public VideoAnalysisConsumer(AiService aiService,
                                 RedissonClient redissonClient,
                                 StringRedisTemplate redisTemplate,
                                 AgentCheckpointService checkpointService,
                                 RocketMQTemplate rocketMQTemplate,
                                 FailedAnalysisTaskService failedTaskService,
                                 MediaService mediaService,
                                 TaskEventService taskEventService,
                                 @Value("${rocketmq.topic.video-analysis-dead:video-analysis-dead-topic}")
                                 String deadLetterTopic) {
        this.aiService = aiService;
        this.redissonClient = redissonClient;
        this.redisTemplate = redisTemplate;
        this.checkpointService = checkpointService;
        this.rocketMQTemplate = rocketMQTemplate;
        this.failedTaskService = failedTaskService;
        this.mediaService = mediaService;
        this.taskEventService = taskEventService;
        this.deadLetterTopic = deadLetterTopic;
    }

    @Override
    public void onMessage(AnalysisTaskMsg msg) {
        String rejection = rejectionReason(msg);
        if (rejection != null) {
            discardPoisonMessage(msg, rejection);
            return;
        }
        Long mediaId = msg.getMediaId();
        AnalysisMode mode = AnalysisMode.fromNullable(msg.getMode());
        String contentHash = AnalysisTaskKeys.normalizeContentHash(mediaId, msg.getContentHash());
        String goalDigest = AnalysisTaskKeys.goalDigest(msg.getUserGoal(), mode);
        String lockKey = AnalysisTaskKeys.lock(contentHash, goalDigest);
        String activeKey = AnalysisTaskKeys.active(contentHash, goalDigest);
        String completedKey = AnalysisTaskKeys.completed(contentHash, goalDigest);
        String attemptsKey = AnalysisTaskKeys.attempts(contentHash, goalDigest);
        RLock lock = redissonClient.getLock(lockKey);
        boolean acquired = false;
        boolean retrying = false;
        long attempt = 0;
        try {
            acquired = lock.tryLock();
            if (!acquired) {
                log.info("video_analysis_skipped mediaId={} acquired={}", mediaId, acquired);
                return;
            }
            if (!mediaService.exists(mediaId)) {
                log.info("video_analysis_discarded_deleted_media mediaId={}", mediaId);
                return;
            }
            Long currentAttempt = redisTemplate.opsForValue().increment(attemptsKey);
            attempt = currentAttempt == null ? 1 : currentAttempt;
            redisTemplate.expire(attemptsKey, ACTIVE_TTL);
            taskEventService.publishAnalysis(mediaId, msg.getUserGoal(), mode,
                    TaskStatus.of(TaskStatus.State.PROCESSING, "Video analysis task has started"),
                    TaskStage.CONSUMING);
            if (msg.isRevision()) {
                if (!checkpointService.beginStagedRevision(mediaId, msg.getUserGoal(), mode)) {
                    throw new IllegalStateException("Revision task state does not exist; waiting for message-queue retry");
                }
                redisTemplate.delete(completedKey);
            } else {
                String completedMediaId = redisTemplate.opsForValue().get(completedKey);
                if (completedMediaId != null) {
                    Long sourceMediaId = parseMediaId(completedMediaId, completedKey);
                    AgentState reusable = sourceMediaId == null ? null
                            : checkpointService.loadResult(sourceMediaId, msg.getUserGoal(), mode);
                    if (reusable != null && reusable.result() != null
                            && aiService.reuseResult(mediaId, sourceMediaId, reusable, mode)) {
                        taskEventService.publishAnalysis(mediaId, msg.getUserGoal(), mode,
                                TaskStatus.completed(reusable), TaskStage.COMPLETED_REUSED);
                        log.info("video_analysis_reused mediaId={} sourceMediaId={}", mediaId, sourceMediaId);
                        return;
                    }
                    redisTemplate.delete(completedKey);
                }
            }
            saveStage(mediaId, msg.getUserGoal(), mode, TaskStage.CONSUMING);
            aiService.asyncAnalyze(mediaId, msg.getUserGoal(), mode);
            if (msg.isRevision()) {
                checkpointService.completeStagedRevision(mediaId, msg.getUserGoal(), mode);
            }
            if (!mediaService.exists(mediaId)) {
                mediaService.purgeRuntimeArtifacts(mediaId);
                log.info("video_analysis_cleanup_after_media_deleted mediaId={}", mediaId);
                return;
            }
            redisTemplate.opsForValue().set(
                    completedKey, String.valueOf(mediaId), Duration.ofDays(7));
            AgentState completed = checkpointService.loadResult(mediaId, msg.getUserGoal(), mode);
            if (completed != null && completed.result() != null) {
                taskEventService.publishAnalysis(mediaId, msg.getUserGoal(), mode,
                        TaskStatus.completed(completed), TaskStage.COMPLETED);
            }
        } catch (AgentLoopService.BudgetExceededException e) {
            saveStage(mediaId, msg.getUserGoal(), mode, TaskStage.BUDGET_EXHAUSTED);
            taskEventService.publishAnalysis(mediaId, msg.getUserGoal(), mode,
                    TaskStatus.of(TaskStatus.State.FAILED, e.getMessage()),
                    TaskStage.BUDGET_EXHAUSTED);
            log.warn("video_analysis_budget_exhausted mediaId={} reason={}", mediaId, e.getMessage());
            return;
        } catch (Exception e) {
            boolean permanent = isPermanentFailure(e);
            if (!permanent && acquired && attempt > 0 && attempt < MAX_DELIVERY_ATTEMPTS) {
                retrying = true;
                redisTemplate.expire(activeKey, ACTIVE_TTL);
                saveStage(mediaId, msg.getUserGoal(), mode, TaskStage.RETRYING);
                taskEventService.publishAnalysis(mediaId, msg.getUserGoal(), mode,
                        TaskStatus.of(TaskStatus.State.PROCESSING, "This attempt failed; waiting for message-queue retry"),
                        TaskStage.RETRYING);
                log.warn("video_analysis_retry_scheduled mediaId={} attempt={}", mediaId, attempt, e);
                throw new IllegalStateException("Video analysis consumption failed; delegating retry to RocketMQ", e);
            }
            if (acquired && (permanent || attempt >= MAX_DELIVERY_ATTEMPTS)) {
                try {
                    try {
                        failedTaskService.record(msg, attempt, e);
                    } catch (RuntimeException recordError) {
                        e.addSuppressed(recordError);
                        log.error("failed_analysis_record_write_failed mediaId={}", mediaId, recordError);
                    }
                    rocketMQTemplate.convertAndSend(deadLetterTopic, msg);
                    saveStage(mediaId, msg.getUserGoal(), mode, TaskStage.DEAD_LETTERED);
                    taskEventService.publishAnalysis(mediaId, msg.getUserGoal(), mode,
                            TaskStatus.of(TaskStatus.State.FAILED, "Analysis failed and entered the manual-review queue"),
                            TaskStage.DEAD_LETTERED);
                    log.error("video_analysis_dead_lettered mediaId={} attempts={} permanent={}",
                            mediaId, attempt, permanent, e);
                    return;
                } catch (RuntimeException deadLetterError) {
                    retrying = true;
                    deadLetterError.addSuppressed(e);
                    log.error("video_analysis_dead_letter_dispatch_failed mediaId={}", mediaId, deadLetterError);
                    throw deadLetterError;
                }
            }
            log.error("video_analysis_consume_failed mediaId={}", mediaId, e);
            throw new IllegalStateException("Video analysis consumption failed", e);
        } finally {
            if (acquired) {
                if (!retrying) redisTemplate.delete(java.util.List.of(activeKey, attemptsKey));
                if (lock.isHeldByCurrentThread()) {
                    lock.unlock();
                }
            }
        }
    }

    private String rejectionReason(AnalysisTaskMsg msg) {
        if (msg == null) return "Message body is empty";
        if (msg.getMediaId() == null) return "Missing mediaId";
        if (msg.getUserGoal() == null || msg.getUserGoal().isBlank()) return "Missing analysis goal";
        if (!msg.hasSupportedAction()) return "Unsupported action=" + msg.getAction();
        return null;
    }

    /**
     *
     */
    private void discardPoisonMessage(AnalysisTaskMsg msg, String reason) {
        log.error("video_analysis_poison_message reason={} payload={}", reason, describe(msg));
        if (msg == null) return;

        IllegalArgumentException error =
                new IllegalArgumentException("invalid video analysis message: " + reason);
        boolean recorded = false;
        boolean deadLettered = false;
        try {
            failedTaskService.record(msg, 0, error);
            recorded = true;
        } catch (RuntimeException recordError) {
            log.error("poison_message_record_failed payload={}", describe(msg), recordError);
        }
        try {
            rocketMQTemplate.convertAndSend(deadLetterTopic, msg);
            deadLettered = true;
        } catch (RuntimeException dispatchError) {
            log.error("poison_message_dead_letter_failed payload={}", describe(msg), dispatchError);
        }

        if (!recorded && !deadLettered) {
            throw new IllegalStateException(
                    "Poison message cannot be reconciled: neither the failure ledger nor the failure topic is available; acknowledgement was rejected to prevent message loss", error);
        }
        releasePoisonTaskState(msg);
    }

    /**
     */
    private void releasePoisonTaskState(AnalysisTaskMsg msg) {
        if (msg.getMediaId() == null || msg.getUserGoal() == null || msg.getUserGoal().isBlank()) {
            return;
        }
        try {
            AnalysisMode mode = AnalysisMode.fromNullable(msg.getMode());
            String contentHash = AnalysisTaskKeys.normalizeContentHash(
                    msg.getMediaId(), msg.getContentHash());
            String goalDigest = AnalysisTaskKeys.goalDigest(msg.getUserGoal(), mode);
            redisTemplate.delete(java.util.List.of(
                    AnalysisTaskKeys.active(contentHash, goalDigest),
                    AnalysisTaskKeys.attempts(contentHash, goalDigest)));
            saveStage(msg.getMediaId(), msg.getUserGoal(), mode, TaskStage.DEAD_LETTERED);
            taskEventService.publishAnalysis(msg.getMediaId(), msg.getUserGoal(), mode,
                    TaskStatus.of(TaskStatus.State.FAILED, "Task message is invalid and has been terminated"),
                    TaskStage.DEAD_LETTERED);
        } catch (RuntimeException e) {
            log.warn("poison_message_state_release_failed payload={}", describe(msg), e);
        }
    }

    private String describe(AnalysisTaskMsg msg) {
        if (msg == null) return "null";
        return "mediaId=" + msg.getMediaId()
                + " action=" + msg.getAction()
                + " contentHash=" + msg.getContentHash()
                + " goalLength=" + (msg.getUserGoal() == null ? 0 : msg.getUserGoal().length());
    }

    /**
     */
    private boolean isPermanentFailure(Throwable error) {
        Throwable current = error;
        for (int depth = 0; current != null && depth < MAX_CAUSE_DEPTH; depth++) {
            if (current instanceof IllegalArgumentException
                    || current instanceof SecurityException
                    || current instanceof NoSuchElementException) {
                return true;
            }
            if (current.getCause() == current) break;
            current = current.getCause();
        }
        return false;
    }

    private Long parseMediaId(String value, String completedKey) {
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException e) {
            redisTemplate.delete(completedKey);
            log.warn("invalid_completed_media_reference key={} value={}", completedKey, value);
            return null;
        }
    }

    private void saveStage(Long mediaId, String goal, AnalysisMode mode, TaskStage stage) {
        try {
            checkpointService.saveStage(mediaId, goal, mode, stage);
        } catch (RuntimeException e) {
            log.warn("analysis_stage_checkpoint_failed mediaId={} stage={}", mediaId, stage, e);
        }
    }
}
