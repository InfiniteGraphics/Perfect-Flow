package com.perfectflow.audio;

import java.util.Properties;

public record SystemAudioMetadata(
        int sampleRate,
        int channels,
        String sampleFormat,
        long totalFrames
) {
    public static final String DEFAULT_SAMPLE_FORMAT = "s16le";

    public long bytesPerFrame() {
        return switch (sampleFormat) {
            case "u8" -> channels;
            case "s16le" -> channels * 2L;
            case "s24le" -> channels * 3L;
            case "s32le", "f32le" -> channels * 4L;
            default -> throw new IllegalArgumentException("Unsupported sample format: " + sampleFormat);
        };
    }

    public double durationSeconds() {
        return sampleRate <= 0 ? 0.0D : totalFrames / (double) sampleRate;
    }

    public static SystemAudioMetadata fromProperties(Properties properties) {
        int sampleRate = parseInt(properties, "sampleRate", 48_000);
        int channels = parseInt(properties, "channels", 2);
        String sampleFormat = properties.getProperty("sampleFormat", DEFAULT_SAMPLE_FORMAT).trim();
        long totalFrames = parseLong(properties, "totalFrames", 0L);
        return new SystemAudioMetadata(sampleRate, channels, sampleFormat, totalFrames);
    }

    private static int parseInt(Properties properties, String key, int fallback) {
        String value = properties.getProperty(key, "").trim();
        if (value.isEmpty()) {
            return fallback;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static long parseLong(Properties properties, String key, long fallback) {
        String value = properties.getProperty(key, "").trim();
        if (value.isEmpty()) {
            return fallback;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }
}
