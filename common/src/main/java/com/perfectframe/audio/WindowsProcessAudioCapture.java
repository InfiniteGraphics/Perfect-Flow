package com.perfectframe.audio;

import com.perfectframe.capture.CaptureSession;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

public final class WindowsProcessAudioCapture implements SystemAudioCapture {
    private static final Duration START_TIMEOUT = Duration.ofSeconds(12);
    private static final Duration STOP_TIMEOUT = Duration.ofSeconds(8);
    private static final String HELPER_RESOURCE = "perfectframe/audio/windows-process-loopback.ps1";

    private Process process;
    private BufferedWriter stdin;
    private BufferedReader stdout;
    private Path rawFile;
    private Path metadataFile;
    private Path logFile;
    private String failureDetail = "";

    public static SystemAudioCapture createOrNoop() {
        return isWindows() ? new WindowsProcessAudioCapture() : new NoopSystemAudioCapture();
    }

    @Override
    public boolean isSupported() {
        return isWindows();
    }

    @Override
    public String start(CaptureSession session) {
        stop();
        rawFile = session.audioTempFile();
        metadataFile = session.audioMetadataFile();
        logFile = session.audioCaptureLogFile();
        failureDetail = "";

        try {
            Files.createDirectories(rawFile.getParent());
            Files.deleteIfExists(rawFile);
            Files.deleteIfExists(metadataFile);
            Files.deleteIfExists(logFile);

            Path scriptPath = extractHelperScript(session.outputDirectory());
            List<String> command = new ArrayList<>();
            command.add(resolvePowerShellExecutable());
            command.add("-NoLogo");
            command.add("-NoProfile");
            command.add("-NonInteractive");
            command.add("-ExecutionPolicy");
            command.add("Bypass");
            command.add("-File");
            command.add(scriptPath.toString());
            command.add("-ProcessId");
            command.add(Long.toString(ProcessHandle.current().pid()));
            command.add("-OutputRaw");
            command.add(rawFile.toString());
            command.add("-OutputMeta");
            command.add(metadataFile.toString());

            ProcessBuilder builder = new ProcessBuilder(command);
            builder.redirectError(logFile.toFile());
            process = builder.start();
            stdin = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
            stdout = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));

            long deadline = System.nanoTime() + START_TIMEOUT.toNanos();
            while (System.nanoTime() < deadline) {
                if (!process.isAlive()) {
                    return fail("Audio helper exited before it became ready. Check " + logFile + ".");
                }
                if (stdout.ready()) {
                    String line = stdout.readLine();
                    if (line == null) {
                        return fail("Audio helper closed its output unexpectedly. Check " + logFile + ".");
                    }
                    if (line.startsWith("READY|")) {
                        return null;
                    }
                    if (line.startsWith("ERROR|")) {
                        return fail(line.substring("ERROR|".length()).trim());
                    }
                } else {
                    Thread.sleep(25L);
                }
            }
            return fail("Timed out while waiting for Windows process-audio capture to start.");
        } catch (Exception exception) {
            return fail(exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage());
        }
    }

    @Override
    public void stop() {
        IOException closeFailure = null;
        if (stdin != null) {
            try {
                stdin.write("q");
                stdin.newLine();
                stdin.flush();
            } catch (IOException exception) {
                closeFailure = exception;
            }
        }
        if (process != null) {
            try {
                if (!process.waitFor(STOP_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                    process.destroy();
                    if (!process.waitFor(2, TimeUnit.SECONDS)) {
                        process.destroyForcibly();
                        process.waitFor(2, TimeUnit.SECONDS);
                    }
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
            }
        }
        tryClose(stdout);
        tryClose(stdin);
        stdout = null;
        stdin = null;
        process = null;
        if (closeFailure != null && failureDetail.isBlank()) {
            failureDetail = closeFailure.getMessage() == null ? closeFailure.getClass().getSimpleName() : closeFailure.getMessage();
        }
    }

    @Override
    public void advanceFrame(CaptureSession session) {
        if (process == null) {
            return;
        }
        if (!process.isAlive()) {
            session.setAudioStatus(false, true, buildEarlyExitMessage());
            stop();
        }
    }

    private String fail(String detail) {
        failureDetail = detail == null ? "" : detail.trim();
        stop();
        return failureDetail.isBlank()
                ? "Windows process audio capture failed to start."
                : failureDetail;
    }

    private String buildEarlyExitMessage() {
        if (!failureDetail.isBlank()) {
            return failureDetail;
        }
        if (logFile != null) {
            return "Windows process audio capture stopped early. Check " + logFile + ".";
        }
        return "Windows process audio capture stopped early.";
    }

    private Path extractHelperScript(Path outputDirectory) throws IOException {
        Path helperDirectory = outputDirectory.resolve(".perfectflow-helper");
        Files.createDirectories(helperDirectory);
        Path scriptPath = helperDirectory.resolve("windows-process-loopback.ps1");
        try (InputStream inputStream = WindowsProcessAudioCapture.class.getClassLoader().getResourceAsStream(HELPER_RESOURCE)) {
            if (inputStream == null) {
                throw new IOException("Missing helper resource: " + HELPER_RESOURCE);
            }
            Files.copy(inputStream, scriptPath, StandardCopyOption.REPLACE_EXISTING);
        }
        return scriptPath;
    }

    private String resolvePowerShellExecutable() {
        String windir = System.getenv("WINDIR");
        if (windir != null && !windir.isBlank()) {
            Path candidate = Path.of(windir, "System32", "WindowsPowerShell", "v1.0", "powershell.exe");
            if (Files.isRegularFile(candidate)) {
                return candidate.toString();
            }
        }
        return "powershell.exe";
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private void tryClose(AutoCloseable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Exception ignored) {
        }
    }
}
