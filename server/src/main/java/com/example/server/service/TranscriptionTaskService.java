package com.example.server.service;

import com.example.server.dto.TaskStatus;
import com.example.server.dto.TaskStage;
import com.example.server.entity.MediaFile;
import com.example.server.mapper.MediaFileMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Service
public class TranscriptionTaskService {

    private static final Logger log = LoggerFactory.getLogger(TranscriptionTaskService.class);
    private static final Duration ACTIVE_TTL = Duration.ofHours(2);

    private final MediaFileMapper mediaFileMapper;
    private final VideoTranscriptionService videoTranscriptionService;
    private final MediaService mediaService;
    private final StringRedisTemplate redisTemplate;
    private final TaskEventService taskEventService;

    public TranscriptionTaskService(MediaFileMapper mediaFileMapper,
                                    VideoTranscriptionService videoTranscriptionService,
                                    MediaService mediaService,
                                    StringRedisTemplate redisTemplate,
                                    TaskEventService taskEventService) {
        this.mediaFileMapper = mediaFileMapper;
        this.videoTranscriptionService = videoTranscriptionService;
        this.mediaService = mediaService;
        this.redisTemplate = redisTemplate;
        this.taskEventService = taskEventService;
    }

    public boolean queue(Long mediaId) {
        Boolean accepted = redisTemplate.opsForValue().setIfAbsent(activeKey(mediaId), "1", ACTIVE_TTL);
        if (!Boolean.TRUE.equals(accepted)) return false;
        setState(mediaId, TaskStatus.State.QUEUED, ACTIVE_TTL);
        taskEventService.publishTranscription(mediaId,
                TaskStatus.of(TaskStatus.State.QUEUED, "Text extraction task is queued."), TaskStage.QUEUED);
        return true;
    }

    @Async("aiTaskExecutor")
    public void transcribe(Long mediaId) {
        MediaFile mediaFile = mediaFileMapper.selectById(mediaId);
        if (mediaFile == null) {
            clearActive(mediaId);
            return;
        }

        try {
            setState(mediaId, TaskStatus.State.PROCESSING, ACTIVE_TTL);
            taskEventService.publishTranscription(mediaId,
                    TaskStatus.of(TaskStatus.State.PROCESSING, "Recognizing speech from the video."), TaskStage.ASR);
            mediaFile.setTranscriptText(videoTranscriptionService.transcribe(
                    mediaService.readableSource(mediaFile.getFilePath())));
            mediaFileMapper.updateById(mediaFile);
            mediaService.invalidateUserList(mediaFile.getUserId());
            setState(mediaId, TaskStatus.State.COMPLETED, Duration.ofDays(7));
            taskEventService.publishTranscription(mediaId,
                    TaskStatus.completed(mediaFile.getTranscriptText()), TaskStage.COMPLETED);
            log.info("transcription_completed mediaId={}", mediaId);
        } catch (Exception e) {
            setState(mediaId, TaskStatus.State.FAILED, Duration.ofHours(1));
            taskEventService.publishTranscription(mediaId,
                    TaskStatus.of(TaskStatus.State.FAILED, "Text extraction failed. Please try again later."), TaskStage.FAILED);
            log.error("transcription_failed mediaId={}", mediaId, e);
        } finally {
            clearActive(mediaId);
        }
    }

    public void rejectQueued(Long mediaId) {
        clearActive(mediaId);
        setState(mediaId, TaskStatus.State.FAILED, Duration.ofMinutes(10));
        taskEventService.publishTranscription(mediaId,
                TaskStatus.of(TaskStatus.State.FAILED, "Task queue is full. Please try again later."), TaskStage.DISPATCH_FAILED);
    }

    public TaskStatus status(MediaFile mediaFile) {
        if (mediaFile.getTranscriptText() != null && !mediaFile.getTranscriptText().isBlank()) {
            return TaskStatus.completed(mediaFile.getTranscriptText());
        }
        String stateValue = redisTemplate.opsForValue().get(stateKey(mediaFile.getId()));
        if (stateValue == null) {
            boolean active = Boolean.TRUE.equals(redisTemplate.hasKey(activeKey(mediaFile.getId())));
            return active
                    ? TaskStatus.of(TaskStatus.State.PROCESSING, "Extracting text.")
                    : TaskStatus.of(TaskStatus.State.NOT_STARTED, "No text extraction task has been submitted.");
        }
        try {
            return statusFor(TaskStatus.State.valueOf(stateValue), mediaFile);
        } catch (IllegalArgumentException e) {
            log.warn("invalid_transcription_state mediaId={} state={}", mediaFile.getId(), stateValue);
            return TaskStatus.of(TaskStatus.State.NOT_STARTED, "Task status is unavailable.");
        }
    }

    private TaskStatus statusFor(TaskStatus.State state, MediaFile mediaFile) {
        return switch (state) {
            case COMPLETED -> TaskStatus.completed(mediaFile.getTranscriptText());
            case FAILED -> TaskStatus.of(state, "Text extraction failed. Please try again later.");
            case QUEUED -> TaskStatus.of(state, "Text extraction task is queued.");
            case PROCESSING -> TaskStatus.of(state, "Extracting text.");
            case NOT_STARTED -> TaskStatus.of(state, "No text extraction task has been submitted.");
        };
    }

    private void setState(Long mediaId, TaskStatus.State state, Duration ttl) {
        try {
            redisTemplate.opsForValue().set(stateKey(mediaId), state.name(), ttl);
        } catch (RuntimeException e) {
            log.warn("transcription_state_write_failed mediaId={} state={}", mediaId, state, e);
        }
    }

    private void clearActive(Long mediaId) {
        try {
            redisTemplate.delete(activeKey(mediaId));
        } catch (RuntimeException e) {
            log.warn("transcription_active_cleanup_failed mediaId={}", mediaId, e);
        }
    }

    private String activeKey(Long mediaId) {
        return "transcription:active:" + mediaId;
    }

    private String stateKey(Long mediaId) {
        return "transcription:state:" + mediaId;
    }
}
