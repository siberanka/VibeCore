package com.siberanka.vibecore.hud;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.siberanka.vibecore.core.validation.Problem;
import com.siberanka.vibecore.core.validation.Result;
import com.siberanka.vibecore.project.Identifier;
import com.siberanka.vibecore.variable.VariableDefinition;
import com.siberanka.vibecore.variable.VariableFile;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HudCatalogTest {

    @TempDir
    Path dataFolder;

    private void copyBundled(String name) throws IOException {
        Path target = dataFolder.resolve(name);
        Files.createDirectories(target.getParent());
        try (InputStream stream = getClass().getClassLoader().getResourceAsStream(name)) {
            Files.write(target, stream.readAllBytes());
        }
    }

    private void write(String name, String yaml) throws IOException {
        Path file = dataFolder.resolve(name);
        Files.createDirectories(file.getParent());
        Files.writeString(file, yaml, StandardCharsets.UTF_8);
    }

    @BeforeEach
    void setUp() throws IOException {
        copyBundled("variables.yml");
        copyBundled("huds/example.yml");
        copyBundled("popups/example.yml");
    }

    private Result<HudCatalog> load() {
        Map<Identifier, VariableDefinition> variables = new LinkedHashMap<>();
        VariableFile.load(dataFolder.resolve("variables.yml")).orThrow()
                .forEach(variable -> variables.put(variable.key(), variable));
        return HudCatalog.load(dataFolder, variables, material -> material.equals("diamond"));
    }

    private List<String> codes() {
        return load().problems().stream().map(Problem::code).toList();
    }

    @Test
    void bundledExamplesAreValid() {
        HudCatalog catalog = load().orThrow();
        assertEquals(1, catalog.huds().size());
        HudDefinition.Bar bar = catalog.huds().getFirst().bossbar();
        assertEquals("notched_10", bar.overlay());
        assertEquals(3000, catalog.popups().get(new Identifier("vibecore", "example")).millis());
    }

    @Test
    void hudsAreOrderedByPriorityThenId() throws IOException {
        write("huds/b.yml", "schema-version: 1\npriority: 5\nactionbar: b\n");
        write("huds/a.yml", "schema-version: 1\npriority: 5\nactionbar: a\n");
        write("huds/top.yml", "schema-version: 1\npriority: 50\nactionbar: top\n");
        List<String> order = load().orThrow().huds().stream().map(hud -> hud.id().path()).toList();
        assertEquals(List.of("top", "a", "b", "example"), order);
    }

    @Test
    void invalidHudsAndPopupsAreReported() throws IOException {
        write("huds/empty.yml", "schema-version: 1\n");
        write("huds/bar.yml", """
                schema-version: 1
                bossbar: {text: x, color: orange, overlay: dots, value: {var: missing}}
                when: {has-item: {material: dirt}}
                refresh-ticks: 1
                """);
        write("popups/long.yml", "schema-version: 1\nsurface: actionbar\ntext: x\nsubtitle: y\nseconds: 900\n");
        write("popups/blank.yml", "schema-version: 1\nsurface: chat\n");
        List<String> codes = codes();
        assertTrue(codes.containsAll(List.of("empty", "format", "range", "unknown-variable", "unknown-material",
                "missing")), codes.toString());
    }
}
