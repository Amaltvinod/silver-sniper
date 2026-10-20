package com.albionbot.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Shared JSON setup for the bot's small state files (guild settings, access lists). */
public final class JsonFiles {
    private JsonFiles() {}

    public static final ObjectMapper MAPPER = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    /** Writes to a temp file and moves it into place, so a crash mid-write never leaves a half-written file. */
    public static void writeAtomic(Path path, Object value) {
        try {
            Path dir = path.toAbsolutePath().getParent();
            if (dir != null) Files.createDirectories(dir);
            Path tmp = Files.createTempFile(dir, path.getFileName().toString(), ".tmp");
            MAPPER.writeValue(tmp.toFile(), value);
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not save " + path, e);
        }
    }
}
