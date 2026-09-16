package com.example.server.service;

import com.example.server.entity.MediaFile;
import com.example.server.dto.VideoContext;
import com.example.server.utils.SubtitleImport;
import com.example.server.utils.MinioUtils;
import com.example.server.utils.YtDlpUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.util.List;

@Service
public class MediaIngestService {

    private static final Logger log = LoggerFactory.getLogger(MediaIngestService.class);

    private final MinioUtils minioUtils;
    private final YtDlpUtils ytDlpUtils;
    private final MediaService mediaService;
    private final AgentCheckpointService checkpointService;

    public MediaIngestService(MinioUtils minioUtils,
                              YtDlpUtils ytDlpUtils,
                              MediaService mediaService,
                              AgentCheckpointService checkpointService) {
        this.minioUtils = minioUtils;
        this.ytDlpUtils = ytDlpUtils;
        this.mediaService = mediaService;
        this.checkpointService = checkpointService;
    }

    public MediaFile ingestFile(MultipartFile file, Long userId) throws Exception {
        if (file == null || file.isEmpty()) throw new IllegalArgumentException("Uploaded file is required");

        String filename = mediaService.normalizeVideoFilename(file.getOriginalFilename());
        String md5 = mediaService.calculateMd5(file);
        String fileUrl = minioUtils.uploadFile(file);
        return mediaService.saveUploadedMedia(filename, fileUrl, userId, md5);
    }

    public MediaFile ingestUrl(String url, Long userId) throws Exception {
        if (url == null || url.isBlank()) throw new IllegalArgumentException("Video URL is required");

        SubtitleImport subtitles = ytDlpUtils.downloadSubtitles(url);
        if (subtitles.hasTranscript()) {
            String filename = filenameFor(subtitles.title());
            String contentHash = mediaService.calculateMd5(url + "\n" + subtitles.segments());
            MediaFile media = mediaService.saveSubtitleFirstMedia(
                    filename, url, userId, contentHash, subtitles.durationMs());
            checkpointService.saveContext(media.getId(), new VideoContext(url, "", subtitles.segments().stream()
                    .map(segment -> new VideoContext.VideoSegment(
                            segment.startMs(), segment.endMs(), segment.text(), List.of(), List.of()))
                    .toList()));
            log.info("subtitle_first_import_completed mediaId={} language={} segments={}",
                    media.getId(), subtitles.language(), subtitles.segments().size());
            return media;
        }

        File tempFile = null;
        try {
            tempFile = ytDlpUtils.downloadVideo(url);
            String md5 = mediaService.calculateMd5(tempFile);
            String fileUrl = minioUtils.uploadLocalFile(tempFile);
            MediaFile media = mediaService.saveUploadedMedia("WEB_" + tempFile.getName(), fileUrl, userId, md5);
            return mediaService.attachRemoteSource(media, url, subtitles.durationMs());
        } finally {
            if (tempFile != null && tempFile.exists() && !tempFile.delete()) {
                log.warn("temporary_video_cleanup_failed path={}", tempFile.getAbsolutePath());
            }
        }
    }

    public MediaFile prepareFullVideo(Long mediaId, Long userId) throws Exception {
        MediaFile media = mediaService.requireOwnedMedia(mediaId, userId);
        if (!"SUBTITLE_FIRST".equals(media.getIngestionMode())) return media;
        File tempFile = null;
        try {
            String sourceUrl = media.getSourceUrl() == null ? media.getFilePath() : media.getSourceUrl();
            tempFile = ytDlpUtils.downloadVideo(sourceUrl);
            String contentHash = mediaService.calculateMd5(tempFile);
            String fileUrl = minioUtils.uploadLocalFile(tempFile, media.getFilename());
            mediaService.purgeRuntimeArtifacts(media.getId());
            return mediaService.promoteToFullVideo(media, fileUrl, contentHash);
        } finally {
            if (tempFile != null && tempFile.exists() && !tempFile.delete()) {
                log.warn("temporary_video_cleanup_failed path={}", tempFile.getAbsolutePath());
            }
        }
    }

    private String filenameFor(String title) {
        String base = title == null ? "" : title.replaceAll("[^\\p{L}\\p{N}._ -]", " ")
                .replaceAll("\\s+", " ").trim();
        if (base.isBlank()) base = "WEB_VIDEO";
        if (base.length() > 240) base = base.substring(0, 240).trim();
        return base + ".mp4";
    }
}
