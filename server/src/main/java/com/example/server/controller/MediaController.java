package com.example.server.controller;

import com.example.server.common.Result;
import com.example.server.dto.MediaSummary;
import com.example.server.entity.MediaFile;
import com.example.server.service.AuthService;
import com.example.server.service.ChunkUploadService;
import com.example.server.service.MediaIngestService;
import com.example.server.service.MediaService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Set;

@RestController
@RequestMapping("/media")
public class MediaController {

    private final ChunkUploadService chunkUploadService;
    private final MediaIngestService mediaIngestService;
    private final MediaService mediaService;

    public MediaController(ChunkUploadService chunkUploadService,
                           MediaIngestService mediaIngestService,
                           MediaService mediaService) {
        this.chunkUploadService = chunkUploadService;
        this.mediaIngestService = mediaIngestService;
        this.mediaService = mediaService;
    }

    @PostMapping("/init-upload")
    public Result<String> initUpload(@RequestParam String filename,
                                     @RequestParam int totalChunks,
                                     @RequestAttribute(AuthService.REQUEST_USER_ID) Long userId) throws Exception {
        return Result.ok(chunkUploadService.initialize(filename, totalChunks, userId));
    }

    @GetMapping("/upload-status")
    public Result<Set<Integer>> uploadStatus(
            @RequestParam String uploadId,
            @RequestAttribute(AuthService.REQUEST_USER_ID) Long userId) {
        return Result.ok(chunkUploadService.uploadedChunks(uploadId, userId));
    }

    @PostMapping("/upload-chunk")
    public Result<Void> uploadChunk(@RequestParam String uploadId,
                                    @RequestParam int chunkIndex,
                                    @RequestParam int totalChunks,
                                    @RequestParam("file") MultipartFile file,
                                    @RequestAttribute(AuthService.REQUEST_USER_ID) Long userId) throws Exception {
        chunkUploadService.uploadChunk(uploadId, chunkIndex, totalChunks, file, userId);
        return Result.ok();
    }

    @PostMapping("/complete-upload")
    public Result<MediaSummary> completeUpload(
            @RequestParam String uploadId,
            @RequestAttribute(AuthService.REQUEST_USER_ID) Long userId) throws Exception {
        return Result.ok(MediaSummary.from(chunkUploadService.complete(uploadId, userId)));
    }

    @PostMapping("/upload")
    public Result<MediaSummary> upload(@RequestParam("file") MultipartFile file,
                                       @RequestAttribute(AuthService.REQUEST_USER_ID) Long userId) throws Exception {
        return Result.ok(MediaSummary.from(mediaIngestService.ingestFile(file, userId)));
    }

    @PostMapping("/upload-url")
    public Result<MediaSummary> uploadUrl(@RequestParam("url") String url,
                                          @RequestAttribute(AuthService.REQUEST_USER_ID) Long userId) throws Exception {
        return Result.ok(MediaSummary.from(mediaIngestService.ingestUrl(url, userId)));
    }

    @GetMapping("/list")
    public Result<List<MediaSummary>> getList(
            @RequestAttribute(AuthService.REQUEST_USER_ID) Long userId) {
        return Result.ok(mediaService.listByUser(userId).stream()
                .map(MediaSummary::from)
                .toList());
    }

    @GetMapping("/playback")
    public Result<String> playback(@RequestParam Long id,
                                   @RequestAttribute(AuthService.REQUEST_USER_ID) Long userId) {
        MediaFile mediaFile = mediaService.requireOwnedMedia(id, userId);
        if ("SUBTITLE_FIRST".equals(mediaFile.getIngestionMode())) {
            throw new IllegalStateException("This fast import has a transcript but no local video preview. Select Prepare visual analysis to download the full source when you need visual evidence.");
        }
        return Result.ok(mediaService.readableSource(mediaFile.getFilePath()));
    }

    @PostMapping("/prepare-full-video")
    public Result<MediaSummary> prepareFullVideo(@RequestParam Long id,
                                                  @RequestAttribute(AuthService.REQUEST_USER_ID) Long userId) throws Exception {
        return Result.ok(MediaSummary.from(mediaIngestService.prepareFullVideo(id, userId)));
    }

    @DeleteMapping("/delete")
    public Result<Void> delete(@RequestParam("id") Long id,
                               @RequestAttribute(AuthService.REQUEST_USER_ID) Long userId) {
        mediaService.deleteOwnedMedia(id, userId);
        return Result.ok();
    }
}
