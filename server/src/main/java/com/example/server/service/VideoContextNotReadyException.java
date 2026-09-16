package com.example.server.service;

public class VideoContextNotReadyException extends RuntimeException {

    public VideoContextNotReadyException() {
        super("Video content is not ready. Complete a Video Agent analysis first.");
    }
}
