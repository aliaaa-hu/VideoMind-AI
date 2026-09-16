package com.example.server.service;

import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;

@Component
public class VideoTranscriptionService {

    private final SegmentedTranscriptionService transcriptionService;

    public VideoTranscriptionService(SegmentedTranscriptionService transcriptionService) {
        this.transcriptionService = transcriptionService;
    }

    public String transcribe(String videoPath) {
        return processVideoToText(videoPath);
    }

    private String processVideoToText(String inputPath) {
        if (inputPath == null || inputPath.isBlank()) throw new IllegalArgumentException("Video path is empty");
        if (!inputPath.startsWith("http") && !Files.isRegularFile(Path.of(inputPath))) {
            throw new IllegalArgumentException("Video file does not exist");
        }

        return transcriptionService.transcribeToText(inputPath);
    }
}
