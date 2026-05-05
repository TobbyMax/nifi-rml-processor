package com.agreev.nifi.rml.util;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class TempFiles {

    private TempFiles() { }

    public static Path ensureDir(Path workingDirectory) {
        try {
            return Files.createDirectories(workingDirectory);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot create working directory: " + workingDirectory, e);
        }
    }

    public static Path createTempFile(Path workingDirectory, String prefix, String suffix) {
        try {
            return Files.createTempFile(ensureDir(workingDirectory), prefix, suffix);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot create temp file in: " + workingDirectory, e);
        }
    }

    public static void deleteSilently(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignore) {
            // best-effort cleanup
        }
    }
}
