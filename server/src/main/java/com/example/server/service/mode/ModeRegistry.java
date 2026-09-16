package com.example.server.service.mode;

import com.example.server.dto.AnalysisMode;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 *
 *
 */
@Component
public class ModeRegistry {

    private final Map<AnalysisMode, ModeProfile> profiles = new EnumMap<>(AnalysisMode.class);

    public ModeRegistry() {
        register(new ModeProfile(AnalysisMode.GENERAL, "General analysis",
                "", "", "", List.of()));

        register(new ModeProfile(AnalysisMode.LEARNING, "Learning and review",
                "Organize tasks by knowledge topic rather than chronology, covering concepts, principles, and common confusions.",
                "In addition to conclusions, return sections for key=outline, key=keypoints, key=quiz (with answers), and key=pitfalls.",
                "Check that concepts form a coherent system, explanations do not skip steps, and quizzes cover core concepts.",
                List.of("outline", "keypoints", "quiz", "pitfalls")));

        register(new ModeProfile(AnalysisMode.REVIEW, "Content review",
                "Break the goal into verifiable review items for each major claim.",
                "In addition to conclusions, return sections for key=fallacies, key=exaggerations, key=omissions, and key=doubtful with reasons.",
                "Apply a stricter standard: validate evidence sufficiency, conceptual consistency, and evidence support; reject unsupported claims.",
                List.of("fallacies", "exaggerations", "omissions", "doubtful")));

        register(new ModeProfile(AnalysisMode.CREATION, "Content creation",
                "Break work down around publishable assets: highlights, clip candidates, and distribution hooks.",
                "In addition to conclusions, return sections for key=highlights with timestamps, key=titles, key=intro, and key=script.",
                "Verify every highlight has timestamp evidence and all copy reflects the actual video without fabrication.",
                List.of("highlights", "titles", "intro", "script")));

        for (AnalysisMode value : AnalysisMode.values()) {
            if (!profiles.containsKey(value)) {
                throw new IllegalStateException("No ModeProfile is registered for AnalysisMode: " + value);
            }
        }
    }

    private void register(ModeProfile profile) {
        profiles.put(profile.mode(), profile);
    }

    public ModeProfile of(AnalysisMode mode) {
        return profiles.getOrDefault(mode, profiles.get(AnalysisMode.GENERAL));
    }
}
