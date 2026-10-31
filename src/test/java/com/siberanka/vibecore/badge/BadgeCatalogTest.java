package com.siberanka.vibecore.badge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.siberanka.vibecore.core.validation.Problem;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BadgeCatalogTest {

    @TempDir
    Path dataFolder;

    private void badge(String name, String yaml) throws IOException {
        Path file = dataFolder.resolve("badges").resolve(name + ".yml");
        Files.createDirectories(file.getParent());
        Files.writeString(file, yaml, StandardCharsets.UTF_8);
    }

    @Test
    void badgesReferToPackGlyphsAndCanBeSearched() throws IOException {
        badge("vip", "schema-version: 1\nglyph: vip\nname: \"<gold>VIP\"\n");
        badge("mvp", "schema-version: 1\nglyph: mvp\nname: \"<aqua>Most İyi Oyuncu\"\n");
        BadgeCatalog catalog = BadgeCatalog.load(dataFolder, Set.of("vip", "mvp")).orThrow();
        assertEquals(List.of("mvp", "vip"), catalog.search("").stream().map(badge -> badge.id().path()).toList());
        assertEquals(List.of("vip"), catalog.search("VIP").stream().map(badge -> badge.id().path()).toList());
        assertEquals(1, catalog.search("iyi").size());
    }

    @Test
    void unknownGlyphsAndKeysAreRejected() throws IOException {
        badge("ghost", "schema-version: 1\nglyph: ghost\ngroup: admin\n");
        List<String> codes = BadgeCatalog.load(dataFolder, Set.of("vip")).problems().stream()
                .map(Problem::code).toList();
        assertTrue(codes.containsAll(List.of("unknown-glyph", "unknown-key")), codes.toString());
    }
}
