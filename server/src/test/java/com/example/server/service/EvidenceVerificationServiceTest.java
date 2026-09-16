package com.example.server.service;

import com.example.server.dto.AnalysisResult;
import com.example.server.dto.VideoContext;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EvidenceVerificationServiceTest {

    private final EvidenceVerificationService service = new EvidenceVerificationService();
    private final VideoContext context = new VideoContext(
            "lesson.mp4",
            "\u603B\u7ED3\u8BFE\u7A0B",
            List.of(new VideoContext.VideoSegment(
                    120_000,
                    180_000,
                    "\u63A5\u4E0B\u6765\u8BB2\u89E3\u4E8C\u53C9\u6811\u7684\u524D\u5E8F\u904D\u5386",
                    List.of("\u524D\u5E8F\u904D\u5386：\u6839\u8282\u70B9\u3001\u5DE6\u5B50\u6811\u3001\u53F3\u5B50\u6811"),
                    List.of("frame_000125.jpg"))));

    @Test
    void acceptsVerbatimEvidenceAtTheDeclaredTimestamp() {
        AnalysisResult.Evidence evidence = new AnalysisResult.Evidence(
                125_000, "OCR", "\u6839\u8282\u70B9\u3001\u5DE6\u5B50\u6811\u3001\u53F3\u5B50\u6811", "\u524D\u5E8F\u904D\u5386\u987A\u5E8F");

        assertTrue(service.supported(context, evidence));
        assertTrue(service.supportsClaim(context, "\u524D\u5E8F\u904D\u5386\u987A\u5E8F", evidence));
    }

    @Test
    void rejectsTextThatOnlyLooksSimilarToTheSource() {
        AnalysisResult.Evidence evidence = new AnalysisResult.Evidence(
                125_000, "OCR", "\u6839\u8282\u70B9\u5DE6\u5B50\u6811\u4E0D\u5B58\u5728，\u56E0\u6B64\u5E94\u8DF3\u8FC7", "\u524D\u5E8F\u904D\u5386\u987A\u5E8F");

        assertFalse(service.supported(context, evidence));
    }
}
