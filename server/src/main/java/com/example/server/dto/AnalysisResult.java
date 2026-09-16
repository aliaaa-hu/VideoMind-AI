package com.example.server.dto;

import java.util.List;

/**
 */
public record AnalysisResult(
        String title,
        List<String> conclusions,
        List<Evidence> evidence,
        List<String> suggestions,
        List<Section> sections
) {
    public AnalysisResult {
        title = title == null ? "Untitled analysis" : title.trim();
        conclusions = conclusions == null ? List.of() : List.copyOf(conclusions);
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
        suggestions = suggestions == null ? List.of() : List.copyOf(suggestions);
        sections = sections == null ? List.of() : List.copyOf(sections);
    }

    public record Evidence(
            long timestampMs,
            String source,
            String content,
            String claim
    ) {
        public Evidence {
            if (timestampMs < 0) throw new IllegalArgumentException("evidence timestamp cannot be negative");
            source = source == null ? "UNKNOWN" : source.trim();
            content = content == null ? "" : content.trim();
            claim = claim == null ? "" : claim.trim();
        }
    }

    /**
     */
    public record Section(String key, String title, List<String> items) {
        public Section {
            key = key == null ? "" : key.trim();
            title = title == null ? "" : title.trim();
            items = items == null ? List.of() : List.copyOf(items);
        }
    }

    public String toMarkdown() {
        StringBuilder result = new StringBuilder("## ").append(title).append("\n\n## Key Findings\n");
        conclusions.forEach(item -> result.append("- ").append(item).append('\n'));
        result.append("\n## Video Evidence\n");
        evidence.forEach(item -> result.append("- [")
                .append(formatTime(item.timestampMs()))
                .append("] ")
                .append(item.source())
                .append("：")
                .append(item.content())
                .append('\n'));
        result.append("\n## Recommendations\n");
        suggestions.forEach(item -> result.append("- ").append(item).append('\n'));
        for (Section section : sections) {
            result.append("\n## ").append(section.title()).append('\n');
            section.items().forEach(item -> result.append("- ").append(item).append('\n'));
        }
        return result.toString();
    }

    private static String formatTime(long timestampMs) {
        long seconds = timestampMs / 1000;
        return String.format("%02d:%02d", seconds / 60, seconds % 60);
    }
}
