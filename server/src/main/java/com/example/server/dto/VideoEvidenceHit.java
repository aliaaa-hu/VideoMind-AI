package com.example.server.dto;

import java.util.List;

public record VideoEvidenceHit(
        long startMs,
        long endMs,
        String source,
        String snippet,
        String transcript,
        List<String> ocrTexts
) {
    public VideoEvidenceHit {
        source = source == null ? "" : source;
        snippet = snippet == null ? "" : snippet;
        transcript = transcript == null ? "" : transcript;
        ocrTexts = ocrTexts == null ? List.of() : List.copyOf(ocrTexts);
    }
}
