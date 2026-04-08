package com.debugtools.desktop;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

final class AdbBridge {
    private static final int COMMAND_TIMEOUT_SECONDS = 5;

    private AdbBridge() {
    }

    static PrepareResult prepareForwards(int remotePort) {
        if (!isAdbAvailable()) {
            return new PrepareResult(false, List.of(), List.of("adb not found on PATH, skip USB auto-forward"));
        }

        List<String> onlineDevices = listOnlineDevices();
        if (onlineDevices.isEmpty()) {
            return new PrepareResult(true, List.of(), List.of("No online adb device, skip USB auto-forward"));
        }

        List<ForwardRule> rules = listForwardRules();
        List<ForwardCandidate> candidates = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        for (String serial : onlineDevices) {
            Integer localPort = findLocalPort(rules, serial, remotePort);
            boolean reused = localPort != null;
            if (localPort == null) {
                CommandResult created = runCommand("adb", "-s", serial, "forward", "tcp:0", "tcp:" + remotePort);
                if (!created.success) {
                    notes.add("adb forward failed for " + serial + ": " + created.stderr);
                    continue;
                }
                rules = listForwardRules();
                localPort = findLocalPort(rules, serial, remotePort);
            }
            if (localPort == null) {
                notes.add("adb forward result missing for " + serial + " tcp:" + remotePort);
                continue;
            }
            String source = reused ? "adb:" + serial + " (reused)" : "adb:" + serial;
            candidates.add(new ForwardCandidate("127.0.0.1", localPort, source));
            notes.add("USB ready: " + serial + " -> 127.0.0.1:" + localPort + " to device:" + remotePort);
        }

        return new PrepareResult(true, candidates, notes);
    }

    private static boolean isAdbAvailable() {
        CommandResult result = runCommand("adb", "version");
        return result.success;
    }

    private static List<String> listOnlineDevices() {
        CommandResult result = runCommand("adb", "devices");
        List<String> serials = new ArrayList<>();
        if (!result.success) {
            return serials;
        }
        String[] lines = result.stdout.split("\\R");
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("List of devices")) {
                continue;
            }
            String[] parts = trimmed.split("\\s+");
            if (parts.length >= 2 && "device".equals(parts[1])) {
                serials.add(parts[0]);
            }
        }
        return serials;
    }

    private static List<ForwardRule> listForwardRules() {
        CommandResult result = runCommand("adb", "forward", "--list");
        List<ForwardRule> rules = new ArrayList<>();
        if (!result.success) {
            return rules;
        }
        String[] lines = result.stdout.split("\\R");
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            String[] parts = trimmed.split("\\s+");
            if (parts.length < 3) {
                continue;
            }
            Integer localPort = parseTcpPort(parts[1]);
            Integer remotePort = parseTcpPort(parts[2]);
            if (localPort == null || remotePort == null) {
                continue;
            }
            rules.add(new ForwardRule(parts[0], localPort, remotePort));
        }
        return rules;
    }

    private static Integer findLocalPort(List<ForwardRule> rules, String serial, int remotePort) {
        Integer localPort = null;
        for (ForwardRule rule : rules) {
            if (!serial.equals(rule.serial) || rule.remotePort != remotePort) {
                continue;
            }
            localPort = rule.localPort;
        }
        return localPort;
    }

    private static Integer parseTcpPort(String endpoint) {
        if (endpoint == null || !endpoint.startsWith("tcp:")) {
            return null;
        }
        try {
            return Integer.parseInt(endpoint.substring(4));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static CommandResult runCommand(String... args) {
        ProcessBuilder builder = new ProcessBuilder(args);
        builder.redirectErrorStream(false);
        try {
            Process process = builder.start();
            String stdout;
            String stderr;
            try (
                BufferedReader outReader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
                BufferedReader errReader = new BufferedReader(new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))
            ) {
                stdout = readAll(outReader);
                stderr = readAll(errReader);
            }
            boolean finished = process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return new CommandResult(false, stdout, "command timeout");
            }
            return new CommandResult(process.exitValue() == 0, stdout, stderr.trim());
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return new CommandResult(false, "", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    private static String readAll(BufferedReader reader) throws IOException {
        StringBuilder result = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            if (!result.isEmpty()) {
                result.append('\n');
            }
            result.append(line);
        }
        return result.toString();
    }

    static final class PrepareResult {
        final boolean adbAvailable;
        final List<ForwardCandidate> forwardCandidates;
        final List<String> notes;

        private PrepareResult(boolean adbAvailable, List<ForwardCandidate> forwardCandidates, List<String> notes) {
            this.adbAvailable = adbAvailable;
            this.forwardCandidates = forwardCandidates;
            this.notes = notes;
        }
    }

    static final class ForwardCandidate {
        final String host;
        final int port;
        final String source;

        private ForwardCandidate(String host, int port, String source) {
            this.host = host;
            this.port = port;
            this.source = source;
        }
    }

    private static final class ForwardRule {
        private final String serial;
        private final int localPort;
        private final int remotePort;

        private ForwardRule(String serial, int localPort, int remotePort) {
            this.serial = serial;
            this.localPort = localPort;
            this.remotePort = remotePort;
        }
    }

    private static final class CommandResult {
        private final boolean success;
        private final String stdout;
        private final String stderr;

        private CommandResult(boolean success, String stdout, String stderr) {
            this.success = success;
            this.stdout = stdout;
            this.stderr = stderr;
        }
    }
}

