package com.blu.teacher.service;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;

public final class LocalProcess {
    private LocalProcess() {}
    public static void run(java.util.List<String> command, Path workingDir, long timeoutSeconds) throws Exception {
        Process p = new ProcessBuilder(command).directory(workingDir.toFile()).redirectErrorStream(true).start();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Thread reader = new Thread(() -> { try (InputStream in = p.getInputStream()) { in.transferTo(output); } catch (IOException ignored) {} });
        reader.setDaemon(true); reader.start();
        if (!p.waitFor(timeoutSeconds, TimeUnit.SECONDS)) { p.destroyForcibly(); throw new IOException("Command timed out: " + command.get(0)); }
        reader.join(1000);
        if (p.exitValue() != 0) throw new IOException("Command failed: " + output.toString(StandardCharsets.UTF_8));
    }
    public static boolean available(String executable) {
        try { Process p = new ProcessBuilder(executable, "--version").redirectErrorStream(true).start(); return p.waitFor(3, TimeUnit.SECONDS) && p.exitValue()==0; }
        catch (Exception e) { return false; }
    }
}
