package com.example.server.utils;

import com.example.server.dto.TranscriptSegment;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;
import java.net.InetAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Component
public class YtDlpUtils {

    private static final Logger log = LoggerFactory.getLogger(YtDlpUtils.class);

    private final String ytDlpPath;
    private final String ffmpegDir;
    private final long downloadTimeoutSeconds;
    private final int maxDownloadHeight;
    private final ObjectMapper objectMapper;

    public YtDlpUtils(@Value("${tool.ytdlp.path}") String ytDlpPath,
                      @Value("${tool.ffmpeg.dir}") String ffmpegDir,
                      @Value("${tool.ytdlp.download-timeout-seconds:300}") long downloadTimeoutSeconds,
                      @Value("${tool.ytdlp.max-height:720}") int maxDownloadHeight,
                      ObjectMapper objectMapper) {
        this.ytDlpPath = ytDlpPath;
        this.ffmpegDir = ffmpegDir;
        this.downloadTimeoutSeconds = Math.max(30, downloadTimeoutSeconds);
        this.maxDownloadHeight = Math.max(144, Math.min(maxDownloadHeight, 1080));
        this.objectMapper = objectMapper;
    }

    /**
     * Reads source metadata and downloads a subtitle track only. This avoids downloading video bytes
     * for sources that publish captions, so transcript-based analysis can start immediately.
     */
    public SubtitleImport downloadSubtitles(String url) throws Exception {
        validatePublicHttpUrl(url);
        JsonNode metadata = readMetadata(url);
        String language = preferredSubtitleLanguage(metadata);
        String title = metadata.path("title").asText("");
        Long durationMs = metadata.hasNonNull("duration")
                ? Math.round(metadata.path("duration").asDouble() * 1000d)
                : null;
        if (language == null) return new SubtitleImport(title, durationMs, "", List.of());

        Path workDir = Files.createTempDirectory("yt-dlp-subtitles-");
        try {
            List<String> command = new ArrayList<>();
            command.add(ytDlpPath);
            command.add("--no-playlist");
            command.add("--skip-download");
            command.add("--write-subs");
            command.add("--write-auto-subs");
            command.add("--sub-langs");
            command.add(language);
            command.add("--sub-format");
            command.add("vtt");
            command.add("--output");
            command.add(workDir.resolve("captions.%(ext)s").toString());
            command.add(url);
            run(command, "Subtitle download", Math.min(downloadTimeoutSeconds, 90));
            try (var files = Files.list(workDir)) {
                Path subtitle = files.filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".vtt"))
                        .findFirst()
                        .orElse(null);
                List<TranscriptSegment> segments = subtitle == null ? List.of() : parseVtt(Files.readAllLines(subtitle));
                return new SubtitleImport(title, durationMs, language, segments);
            }
        } finally {
            deleteTree(workDir);
        }
    }

    public File downloadVideo(String url) throws Exception {
        validatePublicHttpUrl(url);
        Path outputPath = Path.of(System.getProperty("java.io.tmpdir"), UUID.randomUUID() + ".mp4");
        Path logPath = Files.createTempFile("yt-dlp-", ".log");
        List<String> command = new ArrayList<>();
        command.add(ytDlpPath);
        command.add("--no-playlist");
        command.add("--socket-timeout");
        command.add("30");
        command.add("--retries");
        command.add("3");
        command.add("--max-filesize");
        command.add("2048M");
        // Prefer a browser-friendly H.264/AAC stream at a modest resolution.
        // This avoids downloading 1080p/4K video and avoids a second full-video recode.
        command.add("-f");
        command.add("bv*[height<=" + maxDownloadHeight + "][vcodec^=avc1][ext=mp4]"
                + "+ba[acodec^=mp4a][ext=m4a]/b[height<=" + maxDownloadHeight
                + "][vcodec^=avc1][ext=mp4]/bv*[height<=" + maxDownloadHeight
                + "][vcodec^=avc1]+ba[acodec^=mp4a]");
        command.add("--merge-output-format");
        command.add("mp4");
        if (ffmpegDir != null && !ffmpegDir.isBlank()) {
            command.add("--ffmpeg-location");
            command.add(ffmpegDir);
        }
        command.add("-o");
        command.add(outputPath.toString());
        command.add(url);

        Process process = null;
        try {
            process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .redirectOutput(logPath.toFile())
                    .start();
            if (!process.waitFor(downloadTimeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IllegalStateException(
                        "Video URL download timed out after " + downloadTimeoutSeconds
                                + " seconds. Try a shorter public video or upload the file directly.");
            }
            if (process.exitValue() != 0 || !Files.isRegularFile(outputPath)) {
                String logs = Files.readString(logPath);
                throw new IllegalStateException("Video URL download failed: " + diagnoseFailure(logs));
            }
            log.info("url_video_downloaded host={} bytes={}", URI.create(url).getHost(), Files.size(outputPath));
            return outputPath.toFile();
        } catch (Exception e) {
            Files.deleteIfExists(outputPath);
            throw e;
        } finally {
            Files.deleteIfExists(logPath);
            if (process != null && process.isAlive()) process.destroyForcibly();
        }
    }

    private JsonNode readMetadata(String url) throws Exception {
        Path output = Files.createTempFile("yt-dlp-metadata-", ".json");
        try {
            List<String> command = List.of(ytDlpPath, "--no-playlist", "--skip-download", "--dump-single-json", "--no-warnings", url);
            run(command, "Metadata lookup", Math.min(downloadTimeoutSeconds, 60), output);
            return objectMapper.readTree(Files.readString(output));
        } finally {
            Files.deleteIfExists(output);
        }
    }

    private String preferredSubtitleLanguage(JsonNode metadata) {
        for (String source : List.of("subtitles", "automatic_captions")) {
            JsonNode tracks = metadata.path(source);
            if (!tracks.isObject()) continue;
            List<String> languages = new ArrayList<>();
            tracks.fieldNames().forEachRemaining(languages::add);
            for (String language : languages) {
                if (language.equalsIgnoreCase("en") || language.toLowerCase(Locale.ROOT).startsWith("en-")) return language;
            }
            if (!languages.isEmpty()) return languages.getFirst();
        }
        return null;
    }

    private List<TranscriptSegment> parseVtt(List<String> lines) {
        List<TranscriptSegment> segments = new ArrayList<>();
        long startMs = -1;
        long endMs = -1;
        StringBuilder text = new StringBuilder();
        for (String raw : lines) {
            String line = raw == null ? "" : raw.trim();
            if (line.contains("-->")) {
                appendCue(segments, startMs, endMs, text);
                String[] range = line.split("-->", 2);
                startMs = parseTimestamp(range[0]);
                endMs = parseTimestamp(range[1].split("\\s+", 2)[0]);
                text.setLength(0);
            } else if (line.isBlank()) {
                appendCue(segments, startMs, endMs, text);
                startMs = -1;
                endMs = -1;
                text.setLength(0);
            } else if (startMs >= 0 && !line.startsWith("WEBVTT") && !line.startsWith("NOTE")) {
                if (!text.isEmpty()) text.append(' ');
                text.append(line.replaceAll("<[^>]+>", ""));
            }
        }
        appendCue(segments, startMs, endMs, text);
        return segments;
    }

    private void appendCue(List<TranscriptSegment> segments, long startMs, long endMs, StringBuilder text) {
        String value = text.toString().replaceAll("\\s+", " ").trim();
        if (startMs >= 0 && endMs > startMs && !value.isBlank()) {
            if (!segments.isEmpty() && segments.getLast().text().equals(value)) return;
            segments.add(new TranscriptSegment(startMs, endMs, value));
        }
    }

    private long parseTimestamp(String value) {
        String[] pieces = value.trim().replace(',', '.').split(":");
        try {
            double seconds = 0d;
            for (String piece : pieces) seconds = seconds * 60d + Double.parseDouble(piece);
            return Math.round(seconds * 1000d);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private void run(List<String> command, String operation, long timeoutSeconds) throws Exception {
        Path output = Files.createTempFile("yt-dlp-command-", ".log");
        try {
            run(command, operation, timeoutSeconds, output);
        } finally {
            Files.deleteIfExists(output);
        }
    }

    private void run(List<String> command, String operation, long timeoutSeconds, Path output) throws Exception {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(output.toFile()).start();
        try {
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IllegalStateException(operation + " timed out after " + timeoutSeconds + " seconds.");
            }
            if (process.exitValue() != 0) {
                throw new IllegalStateException(operation + " failed: " + diagnoseFailure(Files.readString(output)));
            }
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
    }

    private void deleteTree(Path directory) {
        if (directory == null) return;
        try (var paths = Files.walk(directory)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (Exception e) {
                    log.warn("temporary_subtitle_cleanup_failed path={}", path, e);
                }
            });
        } catch (Exception e) {
            log.warn("temporary_subtitle_directory_cleanup_failed path={}", directory, e);
        }
    }

    private void validatePublicHttpUrl(String value) throws Exception {
        URI uri = URI.create(value);
        String scheme = uri.getScheme();
        String host = uri.getHost();
        if (host == null || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
            throw new IllegalArgumentException("Only valid public HTTP/HTTPS video URLs are supported");
        }
        InetAddress[] resolved = InetAddress.getAllByName(host);
        if (resolved.length == 0) {
            throw new IllegalArgumentException("Unable to resolve the video URL host");
        }
        for (InetAddress address : resolved) {
            if (isDisallowedAddress(address)) {
                throw new IllegalArgumentException("Local, private-network, and reserved-network addresses are not allowed");
            }
        }
    }

    /**
     */
    private boolean isDisallowedAddress(InetAddress address) {
        if (address.isAnyLocalAddress()          // 0.0.0.0 / ::
                || address.isLoopbackAddress()   // 127.0.0.0/8 / ::1
                || address.isLinkLocalAddress()  // 169.254.0.0/16 (including cloud metadata endpoints) / fe80::/10
                || address.isSiteLocalAddress()  // 10/8, 172.16/12, 192.168/16
                || address.isMulticastAddress()) {
            return true;
        }
        byte[] bytes = address.getAddress();
        if (bytes.length == 4) {
            int first = bytes[0] & 0xFF;
            int second = bytes[1] & 0xFF;
            if (first == 0) return true;                                     // 0.0.0.0/8 current network
            if (first == 100 && second >= 64 && second <= 127) return true;  // 100.64.0.0/10 carrier-grade NAT
            if (first == 169 && second == 254) return true;                  // 169.254.0.0/16 link-local fallback
            return first >= 240;                                             // 240.0.0.0/4 reserved range
        }
        if (bytes.length == 16) {
            return (bytes[0] & 0xFE) == 0xFC;                                // fc00::/7 IPv6 unique local address (ULA)
        }
        return false;
    }

    private String tail(String value, int maxLength) {
        return value.length() <= maxLength ? value : value.substring(value.length() - maxLength);
    }

    private String diagnoseFailure(String logs) {
        String details = logs == null ? "" : logs.toLowerCase();
        if (details.contains("sign in") || details.contains("bot") || details.contains("cookies")) {
            return "YouTube rejected automated access. Try a public video, configure yt-dlp cookies, or upload the video file directly.";
        }
        if (details.contains("private video") || details.contains("members-only")) {
            return "The source video is private or restricted. Use a public video or upload a file you are allowed to analyze.";
        }
        if (details.contains("not available") || details.contains("unavailable") || details.contains("geo")) {
            return "The source video is unavailable from this location or no longer accessible.";
        }
        return tail(logs == null || logs.isBlank() ? "yt-dlp returned no diagnostic output." : logs, 600);
    }
}
