package com.example.server.dto;

import java.util.List;

/**
 */
public record VideoChunk(
        long startTime,
        long endTime,
        String segmentSummary,
        List<String> keywords,
        List<VideoContext.VideoSegment> rawSegments,
        List<Double> embedding
) {
    public VideoChunk {
        if (startTime < 0 || endTime <= startTime) throw new IllegalArgumentException("invalid chunk range");
        segmentSummary = segmentSummary == null ? "" : segmentSummary.trim();
        keywords = keywords == null ? List.of() : List.copyOf(keywords);
        rawSegments = rawSegments == null ? List.of() : List.copyOf(rawSegments);
        embedding = embedding == null ? List.of() : List.copyOf(embedding);
    }

    public long startMs() {
        return startTime;
    }

    public long endMs() {
        return endTime;
    }

    public record ChunkSummary(
            String segmentSummary,
            List<String> keywords
    ) {
        public ChunkSummary {
            segmentSummary = segmentSummary == null ? "" : segmentSummary.trim();
            keywords = keywords == null ? List.of() : List.copyOf(keywords);
        }
    }
}
