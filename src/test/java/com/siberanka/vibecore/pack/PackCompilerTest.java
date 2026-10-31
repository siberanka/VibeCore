package com.siberanka.vibecore.pack;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.siberanka.vibecore.core.validation.Problem;
import com.siberanka.vibecore.core.validation.Result;
import com.siberanka.vibecore.pack.registry.StableIdRegistry;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PackCompilerTest {

    @TempDir
    Path dataFolder;

    private static byte[] png(int width, int height) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, 0xFFFF0000);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    private void image(String name, byte[] bytes) throws IOException {
        Path file = dataFolder.resolve("assets/images").resolve(name);
        Files.createDirectories(file.getParent());
        Files.write(file, bytes);
    }

    private void packYml(String yaml) throws IOException {
        Files.createDirectories(dataFolder.resolve("assets"));
        Files.writeString(dataFolder.resolve("assets/pack.yml"), yaml, StandardCharsets.UTF_8);
    }

    private static StableIdRegistry registry() {
        return StableIdRegistry.glyphs("glyph:vibecore", 0xE000, 0xF8FF);
    }

    private static List<String> entries(byte[] zip) throws IOException {
        List<String> names = new ArrayList<>();
        try (ZipInputStream input = new ZipInputStream(new ByteArrayInputStream(zip))) {
            for (ZipEntry entry; (entry = input.getNextEntry()) != null; ) {
                names.add(entry.getName());
            }
        }
        return names;
    }

    private static String entry(byte[] zip, String name) throws IOException {
        try (ZipInputStream input = new ZipInputStream(new ByteArrayInputStream(zip))) {
            for (ZipEntry entry; (entry = input.getNextEntry()) != null; ) {
                if (entry.getName().equals(name)) {
                    return new String(input.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
        }
        throw new AssertionError(name + " missing");
    }

    @Test
    void buildsDeterministicPack() throws IOException {
        image("coin.png", png(8, 8));
        image("ruby.png", png(16, 16));
        packYml("""
                schema-version: 1
                glyphs:
                  coin: {image: coin.png, height: 8, ascent: 7}
                items:
                  ruby: {texture: ruby.png}
                """);
        PackSource source = PackSource.load(dataFolder).orThrow();

        PackCompiler.Output first = PackCompiler.compile(source, registry()).orThrow();
        PackCompiler.Output second = PackCompiler.compile(source, registry()).orThrow();

        assertArrayEquals(first.zip(), second.zip(), "same input must give the same bytes");
        assertEquals(first.sha1(), second.sha1());
        assertEquals(List.of("assets/vibecore/font/glyphs.json", "assets/vibecore/items/ruby.json",
                "assets/vibecore/models/item/ruby.json", "assets/vibecore/textures/font/coin.png",
                "assets/vibecore/textures/item/ruby.png", "pack.mcmeta", "vibecore-mapping.txt"),
                entries(first.zip()));
        assertTrue(entry(first.zip(), "pack.mcmeta").contains("\"pack_format\": 46"));
        assertTrue(entry(first.zip(), "assets/vibecore/font/glyphs.json").contains("\"\""));
        assertTrue(entry(first.zip(), "assets/vibecore/items/ruby.json").contains("vibecore:item/ruby"));
        assertEquals(0xE000, first.glyphs().get("coin"));
    }

    @Test
    void glyphsKeepTheirCodePointsAcrossBuilds() throws IOException {
        image("a.png", png(8, 8));
        image("b.png", png(8, 8));
        packYml("schema-version: 1\nglyphs:\n  a: {image: a.png}\n  b: {image: b.png}\n");
        StableIdRegistry registry = registry();
        PackCompiler.Output both = PackCompiler.compile(PackSource.load(dataFolder).orThrow(), registry).orThrow();

        packYml("schema-version: 1\nglyphs:\n  b: {image: b.png}\n  c: {image: a.png}\n");
        registry.release(new com.siberanka.vibecore.project.Identifier("vibecore", "glyph/a"));
        PackCompiler.Output later = PackCompiler.compile(PackSource.load(dataFolder).orThrow(), registry).orThrow();

        assertEquals(both.glyphs().get("b"), later.glyphs().get("b"));
        assertFalse(later.glyphs().get("c").equals(both.glyphs().get("a")), "a removed glyph's code is not reused");
    }

    @Test
    void rejectsUnsafeOrBrokenAssets() throws IOException {
        image("big.png", png(300, 8));
        image("fake.png", "not a png".getBytes(StandardCharsets.UTF_8));
        byte[] truncated = png(8, 8);
        image("cut.png", java.util.Arrays.copyOf(truncated, truncated.length / 2));
        packYml("""
                schema-version: 1
                glyphs:
                  big: {image: big.png}
                  fake: {image: fake.png}
                  cut: {image: cut.png}
                  escape: {image: ../../config.yml}
                  missing: {image: none.png}
                  Bad-Id: {image: big.png}
                  tall: {image: big.png, height: 4, ascent: 9}
                """);
        Result<PackSource> result = PackSource.load(dataFolder);
        List<String> codes = result.problems().stream().map(Problem::code).toList();
        assertTrue(codes.containsAll(List.of("dimensions", "format", "missing", "range")), codes.toString());
        assertTrue(result.problems().stream().anyMatch(problem -> problem.path().contains("escape")));
    }

    @Test
    void noAssetsGivesAnEmptyButValidPack() {
        PackCompiler.Output output = PackCompiler.compile(PackSource.load(dataFolder).orThrow(), registry()).orThrow();
        assertTrue(output.glyphs().isEmpty());
        assertEquals(40, output.sha1().length());
    }
}
