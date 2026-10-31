package com.siberanka.vibecore.core.io;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AtomicFilesTest {

    @TempDir
    Path root;

    @Test
    void replacesContentWithoutLeavingTemporaryFiles() throws IOException {
        Path target = root.resolve("data/registry.txt");
        AtomicFiles.writeString(target, "first");
        AtomicFiles.writeString(target, "İkinci");

        assertEquals("İkinci", Files.readString(target, StandardCharsets.UTF_8));
        try (Stream<Path> files = Files.list(target.getParent())) {
            assertEquals(1, files.count());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"../escape", "a/../../b", "/absolute", "C:/drive", "a\\b", "a//b", "./a", ""})
    void rejectsEscapingNames(String name) {
        assertThrows(IOException.class, () -> AtomicFiles.resolveContained(root, name));
    }

    @Test
    void resolvesPlainRelativeNames() throws IOException {
        assertEquals(root.toAbsolutePath().normalize().resolve("packs/java/pack.zip"),
                AtomicFiles.resolveContained(root, "packs/java/pack.zip"));
    }
}
