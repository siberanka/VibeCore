package com.siberanka.vibecore.core.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.siberanka.vibecore.core.validation.Problem;
import com.siberanka.vibecore.core.validation.Result;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CoreStateLoaderTest {

    @TempDir
    Path dataFolder;

    private CoreStateLoader loader;

    private static InputStream bundled(String name) {
        return CoreStateLoaderTest.class.getClassLoader().getResourceAsStream(name);
    }

    @BeforeEach
    void setUp() throws IOException {
        try (InputStream config = bundled("config.yml")) {
            Files.write(dataFolder.resolve("config.yml"), config.readAllBytes());
        }
        loader = new CoreStateLoader(dataFolder, CoreStateLoaderTest::bundled);
    }

    @Test
    void bundledDefaultsAreValidAndTranslationsComplete() {
        Result<CoreState> result = loader.load();
        assertTrue(result.isAccepted(), result.problems().toString());
        assertTrue(result.orThrow().settings().enabledModules().isEmpty());
        assertTrue(loader.loadDefaults().isAccepted());
    }

    @Test
    void turkishTextSurvivesLoading() {
        CoreState state = loader.load().orThrow();
        assertTrue(state.messages().template("tr", "command.no-permission").contains("yetkin"));
        assertTrue(state.messages().template("tr", "status.state.failed").contains("başarısız"));
    }

    @Test
    void syntaxErrorIsRejected() throws IOException {
        Files.writeString(dataFolder.resolve("config.yml"), "config-version: [1", StandardCharsets.UTF_8);
        Result<CoreState> result = loader.load();
        assertFalse(result.isAccepted());
        assertEquals("syntax", result.problems().getFirst().code());
    }

    @Test
    void messageOverridesCannotInventKeys() throws IOException {
        Path lang = Files.createDirectories(dataFolder.resolve("lang"));
        Files.writeString(lang.resolve("tr.yml"), "command:\n  no-permission: \"<red>Yetki yok\"\n  invented: x\n",
                StandardCharsets.UTF_8);
        Result<CoreState> result = loader.load();
        assertFalse(result.isAccepted());
        assertTrue(result.problems().stream().map(Problem::code).anyMatch("unknown-message"::equals));

        Files.writeString(lang.resolve("tr.yml"), "command:\n  no-permission: \"<red>Yetki yok\"\n",
                StandardCharsets.UTF_8);
        CoreState state = loader.load().orThrow();
        assertEquals("<red>Yetki yok", state.messages().template("tr", "command.no-permission"));
    }

    @Test
    void unsupportedLocaleFileIsRejected() throws IOException {
        Path lang = Files.createDirectories(dataFolder.resolve("lang"));
        Files.writeString(lang.resolve("de.yml"), "prefix: x\n", StandardCharsets.UTF_8);
        assertFalse(loader.load().isAccepted());
    }
}
