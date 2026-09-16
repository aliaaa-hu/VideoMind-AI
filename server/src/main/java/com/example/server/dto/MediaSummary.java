package com.example.server.dto;

import com.example.server.entity.MediaFile;

import java.time.LocalDateTime;

public record MediaSummary(
        Long id,
        String filename,
        String status,
        String coverUrl,
        LocalDateTime uploadTime,
        String ingestionMode,
        Long sourceDurationMs
) {
    public static MediaSummary from(MediaFile mediaFile) {
        return new MediaSummary(
                mediaFile.getId(),
                mediaFile.getFilename(),
                mediaFile.getStatus(),
                mediaFile.getCoverUrl(),
                mediaFile.getUploadTime(),
                mediaFile.getIngestionMode(),
                mediaFile.getSourceDurationMs());
    }
}
