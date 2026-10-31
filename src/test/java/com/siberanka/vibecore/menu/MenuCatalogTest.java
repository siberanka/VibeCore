package com.siberanka.vibecore.menu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.siberanka.vibecore.core.validation.Problem;
import com.siberanka.vibecore.core.validation.Result;
import com.siberanka.vibecore.project.Identifier;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MenuCatalogTest {

    private static final Set<String> MATERIALS = Set.of("emerald", "diamond", "note_block", "barrier", "oak_door", "wheat", "name_tag",
            "stone");

    @TempDir
    Path dataFolder;

    private void copyBundled(String name) throws IOException {
        Path target = dataFolder.resolve(name);
        Files.createDirectories(target.getParent());
        try (InputStream stream = getClass().getClassLoader().getResourceAsStream(name)) {
            Files.write(target, stream.readAllBytes());
        }
    }

    @BeforeEach
    void setUp() throws IOException {
        copyBundled("variables.yml");
        copyBundled("menus/example.yml");
    }

    private Result<MenuCatalog> load() {
        return MenuCatalog.load(dataFolder, MATERIALS::contains, Set.of("give"));
    }

    private void writeMenu(String path, String yaml) throws IOException {
        Path file = dataFolder.resolve("menus").resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, yaml, StandardCharsets.UTF_8);
    }

    @Test
    void bundledExampleIsValid() {
        Result<MenuCatalog> catalog = load();
        assertTrue(catalog.isAccepted(), catalog.problems().toString());
        MenuDefinition example = catalog.orThrow().menu(new Identifier("vibecore", "example")).orElseThrow();
        assertEquals(5, example.items().size());
        assertEquals(3, catalog.orThrow().variables().size());
    }

    @Test
    void nestedFilesBecomeNestedIdentifiers() throws IOException {
        writeMenu("shop/main.yml", "schema-version: 1\ntitle: Shop\nrows: 1\n");
        assertTrue(load().orThrow().menu(new Identifier("vibecore", "shop/main")).isPresent());
    }

    @Test
    void unknownVariableAndMenuReferencesRejectTheCandidate() throws IOException {
        writeMenu("broken.yml", """
                schema-version: 1
                title: Broken
                rows: 1
                items:
                  a:
                    slot: 0
                    material: stone
                    actions:
                      left:
                        - add: {var: missing/coins, amount: 1}
                        - emit: {type: open, menu: nowhere}
                """);
        Result<MenuCatalog> result = load();
        assertFalse(result.isAccepted());
        assertTrue(result.problems().stream().map(Problem::code).toList()
                .containsAll(java.util.List.of("unknown-variable", "unknown-menu")), result.problems().toString());
    }

    @Test
    void invalidEffectsAndFileNamesAreReported() throws IOException {
        writeMenu("Bad Name.yml", "schema-version: 1\ntitle: x\n");
        writeMenu("effects.yml", """
                schema-version: 1
                title: Effects
                items:
                  a:
                    slot: 0
                    material: stone
                    actions:
                      left:
                        - emit: {type: explode}
                        - emit: {type: sound, sound: "Bad Sound", volume: 9}
                        - emit: {type: message}
                """);
        var codes = load().problems().stream().map(Problem::code).toList();
        assertTrue(codes.containsAll(java.util.List.of("file-name", "unknown-effect", "format", "range", "missing")),
                codes.toString());
    }

    @Test
    void commandsMustBeAllowedAndUseSafeVariables() throws IOException {
        Files.writeString(dataFolder.resolve("variables.yml"), """
                variables:
                  - {key: nick, type: text, scope: session}
                  - {key: level, type: number, scope: session}
                """, StandardCharsets.UTF_8);
        Files.delete(dataFolder.resolve("menus/example.yml"));
        writeMenu("rewards.yml", """
                schema-version: 1
                title: Rewards
                items:
                  a:
                    slot: 0
                    material: stone
                    actions:
                      left:
                        - emit: {type: command, as: console, run: "give <player> stone <var:level>"}
                        - emit: {type: command, as: console, run: "eco give <player> <var:nick>"}
                """);
        writeMenu("invalid.yml", """
                schema-version: 1
                title: Invalid
                items:
                  a:
                    slot: 0
                    material: stone
                    actions:
                      left:
                        - emit: {type: command, as: server, run: "give <player> stone"}
                """);
        var codes = load().problems().stream().map(Problem::code).toList();
        assertTrue(codes.containsAll(java.util.List.of("not-allowed", "type", "format")), codes.toString());
        assertEquals(1, codes.stream().filter("not-allowed"::equals).count(), "give is allowed, eco is not");
    }

    @Test
    void chatInputNeedsAPlayerScopedVariable() throws IOException {
        Files.writeString(dataFolder.resolve("variables.yml"), """
                variables:
                  - {key: draft, type: text, scope: session}
                  - {key: motd, type: text, scope: persistent-global}
                """, StandardCharsets.UTF_8);
        Files.delete(dataFolder.resolve("menus/example.yml"));
        writeMenu("ask.yml", """
                schema-version: 1
                title: Ask
                items:
                  a:
                    slot: 0
                    material: stone
                    actions:
                      left:
                        - emit: {type: input, var: draft, prompt: "Type"}
                        - emit: {type: input, var: motd, prompt: "Type", timeout: 900}
                """);
        var problems = load().problems();
        assertEquals(1, problems.stream().filter(problem -> problem.code().equals("range")).count(), problems.toString());
        writeMenu("ask.yml", """
                schema-version: 1
                title: Ask
                items:
                  a:
                    slot: 0
                    material: stone
                    actions:
                      left:
                        - emit: {type: input, var: draft, prompt: "Type"}
                        - emit: {type: input, var: motd, prompt: "Type"}
                """);
        assertEquals(2, load().problems().stream().filter(problem -> problem.code().equals("scope")).count());
    }

    @Test
    void duplicateVariablesAreRejected() throws IOException {
        Files.writeString(dataFolder.resolve("variables.yml"), """
                variables:
                  - {key: a, type: number, scope: session}
                  - {key: a, type: text, scope: session}
                """, StandardCharsets.UTF_8);
        Files.delete(dataFolder.resolve("menus/example.yml"));
        assertEquals("duplicate", load().problems().getFirst().code());
    }
}
