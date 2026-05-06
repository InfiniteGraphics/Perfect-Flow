package com.perfectframe.capture.ffmpeg;

import com.perfectframe.config.PerfectFrameConfig;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;

public final class FfmpegLocator {
    private FfmpegLocator() {
    }

    public static Path locate(PerfectFrameConfig config) {
        String configuredPath = normalizeConfiguredPath(config.ffmpeg.customPath);
        if (configuredPath.isEmpty()) {
            throw new IllegalStateException("FFmpeg executable path is not configured. Set ffmpeg.customPath to the full path of ffmpeg.exe.");
        }

        final Path path;
        try {
            path = Path.of(configuredPath);
        } catch (InvalidPathException exception) {
            throw new IllegalStateException("FFmpeg executable path is invalid: " + configuredPath, exception);
        }

        if (!path.isAbsolute()) {
            throw new IllegalStateException("FFmpeg executable path must be absolute: " + path);
        }

        if (Files.isDirectory(path)) {
            Path candidate = path.resolve(defaultExecutableName());
            if (isUsableExecutable(candidate)) {
                return candidate.normalize();
            }
            throw new IllegalStateException("FFmpeg path points to a directory but no executable was found inside it: " + path);
        }

        if (!Files.exists(path)) {
            throw new IllegalStateException("FFmpeg executable not found: " + path);
        }
        if (!Files.isRegularFile(path)) {
            throw new IllegalStateException("FFmpeg path is not a file: " + path);
        }
        if (!Files.isExecutable(path)) {
            throw new IllegalStateException("FFmpeg file is not executable: " + path);
        }
        return path.normalize();
    }

    private static boolean isUsableExecutable(Path path) {
        return Files.isRegularFile(path) && Files.isExecutable(path);
    }

    private static String normalizeConfiguredPath(String configuredPath) {
        if (configuredPath == null) {
            return "";
        }
        String value = configuredPath.trim();
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            value = value.substring(1, value.length() - 1).trim();
        }
        return value;
    }

    private static String defaultExecutableName() {
        return System.getProperty("os.name", "").toLowerCase().contains("win") ? "ffmpeg.exe" : "ffmpeg";
    }
}
