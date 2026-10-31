package com.siberanka.vibecore.hologram;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.siberanka.vibecore.core.validation.Problem;
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
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HologramTest {

    private static final Identifier EXAMPLE = new Identifier("vibecore", "example");
    private static final UUID WORLD = UUID.fromString("00000000-0000-0000-0000-0000000000aa");

    @TempDir
    Path dataFolder;

    private void copyBundled(String name) throws IOException {
        Path target = dataFolder.resolve(name);
        Files.createDirectories(target.getParent());
        try (InputStream stream = getClass().getClassLoader().getResourceAsStream(name)) {
            Files.write(target, stream.readAllBytes());
        }
    }

    private Map<Identifier, VariableDefinition> variables() {
        Map<Identifier, VariableDefinition> variables = new LinkedHashMap<>();
        VariableFile.load(dataFolder.resolve("variables.yml")).orThrow()
                .forEach(variable -> variables.put(variable.key(), variable));
        return variables;
    }

    @Test
    void bundledDesignIsValidAndCyclesSlides() throws IOException {
        copyBundled("variables.yml");
        copyBundled("holograms/example.yml");
        HologramDesign design = HologramCatalog.load(dataFolder, variables()).orThrow().design(EXAMPLE).orElseThrow();
        assertEquals(10_000, design.cycleMillis());
        assertEquals(design.slides().get(0), design.slideAt(4_999));
        assertEquals(design.slides().get(1), design.slideAt(5_000));
        assertEquals(design.slides().get(0), design.slideAt(10_000));
        assertEquals(0x40000000, design.background());
    }

    @Test
    void invalidDesignsAreReported() throws IOException {
        copyBundled("variables.yml");
        Path file = dataFolder.resolve("holograms/bad.yml");
        Files.createDirectories(file.getParent());
        Files.writeString(file, """
                schema-version: 1
                billboard: spinning
                view-range: 500
                background: red
                slides:
                  - seconds: 0
                    lines: ["<var:missing/value>"]
                """, StandardCharsets.UTF_8);
        List<String> codes = HologramCatalog.load(dataFolder, variables()).problems().stream()
                .map(Problem::code).toList();
        assertTrue(codes.containsAll(List.of("format", "range", "unknown-variable")), codes.toString());
    }

    @Test
    void placementIdsAreNeverReused() {
        HologramPlacements placements = new HologramPlacements();
        String first = placements.add(EXAMPLE, WORLD, 0, 64, 0).orThrow().id();
        String second = placements.add(EXAMPLE, WORLD, 10, 64, 0).orThrow().id();
        assertTrue(placements.remove(second));
        String third = placements.add(EXAMPLE, WORLD, 20, 64, 0).orThrow().id();
        assertEquals(List.of("h1", "h2", "h3"), List.of(first, second, third));
        assertEquals("h1", placements.nearest(WORLD, 4, 64, 0).orElseThrow().id());
        assertTrue(placements.nearest(UUID.randomUUID(), 0, 0, 0).isEmpty());
    }

    @Test
    void placementsRoundTripAndRejectCorruption() {
        HologramPlacements placements = new HologramPlacements();
        placements.add(EXAMPLE, WORLD, 1.5, 70.25, -3).orThrow();
        placements.add(EXAMPLE, WORLD, 2, 71, 4).orThrow();
        placements.remove("h2");
        String text = placements.serialize();

        HologramPlacements restored = HologramPlacements.deserialize(text).orThrow();
        assertEquals(text, restored.serialize());
        assertEquals("h3", restored.add(EXAMPLE, WORLD, 0, 0, 0).orThrow().id(), "the counter survives a restart");

        assertFalse(HologramPlacements.deserialize("vibecore-holograms 1\nplacement h1 vibecore:x not-a-uuid 0 0 0\n")
                .isAccepted());
        assertFalse(HologramPlacements.deserialize("something else\n").isAccepted());
    }
}
