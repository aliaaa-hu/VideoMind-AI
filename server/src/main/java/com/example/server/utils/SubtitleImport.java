package com.example.server.utils;

import com.example.server.dto.TranscriptSegment;

import java.util.List;

public record SubtitleImport(
        String title,
        Long durationMs,
        String language,
        List<TranscriptSegment> segments
) {
    public SubtitleImport {
        title = title == null ? "" : title.trim();
        language = language == null ? "" : language.trim();
        segments = segments == null ? List.of() : List.copyOf(segments);
    }

    public boolean hasTranscript() {
        return !segments.isEmpty();
    }
}
