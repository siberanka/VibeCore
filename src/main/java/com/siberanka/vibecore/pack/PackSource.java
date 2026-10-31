package com.siberanka.vibecore.pack;

import com.siberanka.vibecore.core.config.CoreStateLoader;
import com.siberanka.vibecore.core.io.AtomicFiles;
import com.siberanka.vibecore.core.validation.MapReader;
import com.siberanka.vibecore.core.validation.Problem;
import com.siberanka.vibecore.core.validation.Result;
import com.siberanka.vibecore.project.MigrationChain;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Pack content declared in {@code assets/pack.yml}, with images read from
 * {@code assets/images/}. Everything is validated before a pack is built.
 *
 * <pre>
 * schema-version: 1
 * glyphs:                       # characters of the vibecore:glyphs font
 *   coin: {image: coin.png, height: 8, ascent: 7}
 * items:                        # item models for the item_model component
 *   ruby: {texture: ruby.png}   # used as model: vibecore:ruby
 * </pre>
 */
public record PackSource(Map<String, Glyph> glyphs, Map<String, Item> items) {

    public static final MigrationChain SCHEMA = MigrationChain.of(1, List.of());
    public static final int MAX_ENTRIES = 512;
    private static final Pattern ID = Pattern.compile("[a-z0-9_]{1,32}");

    public record Glyph(String id, AssetImage image, int height, int ascent) {
    }

    public record Item(String id, AssetImage texture) {
    }

    public PackSource {
        glyphs = Map.copyOf(new TreeMap<>(glyphs));
        items = Map.copyOf(new TreeMap<>(items));
    }

    public static PackSource empty() {
        return new PackSource(Map.of(), Map.of());
    }

    public static Result<PackSource> load(Path dataFolder) {
        Path assets = dataFolder.resolve("assets");
        Path file = assets.resolve("pack.yml");
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            return Result.accepted(empty());
        }
        List<Problem> problems = new ArrayList<>();
        Map<String, Object> document = CoreStateLoader.readYaml(file, "assets/pack.yml", problems);
        if (document == null) {
            return Result.rejected(problems);
        }
        Result<Map<String, Object>> migrated = SCHEMA.migrate(document);
        if (!migrated.isAccepted()) {
            return Result.rejected(prefix(migrated.problems()));
        }
        MapReader root = MapReader.root(migrated.orThrow());
        root.rejectUnknownKeys(Set.of(MigrationChain.VERSION_KEY, "glyphs", "items"));
        Path images = assets.resolve("images");
        Map<String, Glyph> glyphs = new TreeMap<>();
        Map<String, Item> items = new TreeMap<>();
        MapReader glyphSection = root.section("glyphs");
        MapReader itemSection = root.section("items");
        if (glyphSection.keys().size() + itemSection.keys().size() > MAX_ENTRIES) {
            problems.add(new Problem("assets/pack.yml", "size-limit", "more than " + MAX_ENTRIES + " entries"));
            return Result.rejected(problems);
        }
        for (String id : glyphSection.keys()) {
            String path = "assets/pack.yml: glyphs." + id;
            MapReader entry = glyphSection.section(id);
            entry.rejectUnknownKeys(Set.of("image", "height", "ascent"));
            String image = entry.text("image", "", 128);
            int height = entry.integer("height", 8, 1, 256);
            int ascent = entry.integer("ascent", 7, -256, 256);
            if (ascent > height) {
                entry.report("ascent", "range", "must not be greater than height");
            }
            if (!ID.matcher(id).matches()) {
                problems.add(new Problem(path, "format", "ids must match [a-z0-9_]{1,32}"));
            }
            Result<AssetImage> read = readImage(images, image, path + ".image", 256, 256);
            problems.addAll(read.problems());
            if (read.isAccepted() && ID.matcher(id).matches()) {
                glyphs.put(id, new Glyph(id, read.orThrow(), height, ascent));
            }
        }
        for (String id : itemSection.keys()) {
            String path = "assets/pack.yml: items." + id;
            MapReader entry = itemSection.section(id);
            entry.rejectUnknownKeys(Set.of("texture"));
            String texture = entry.text("texture", "", 128);
            if (!ID.matcher(id).matches()) {
                problems.add(new Problem(path, "format", "ids must match [a-z0-9_]{1,32}"));
            }
            Result<AssetImage> read = readImage(images, texture, path + ".texture", 512, 512 * 32);
            problems.addAll(read.problems());
            if (read.isAccepted() && ID.matcher(id).matches()) {
                items.put(id, new Item(id, read.orThrow()));
            }
        }
        problems.addAll(prefix(root.problems()));
        if (!problems.isEmpty()) {
            return Result.rejected(problems);
        }
        return Result.accepted(new PackSource(glyphs, items));
    }

    private static List<Problem> prefix(List<Problem> found) {
        return found.stream()
                .map(problem -> new Problem("assets/pack.yml: " + problem.path(), problem.code(), problem.detail()))
                .distinct().toList();
    }

    private static Result<AssetImage> readImage(Path images, String name, String path, int maxWidth, int maxHeight) {
        if (name.isEmpty() || !name.endsWith(".png")) {
            return Result.rejected(path, "missing", "expected a .png file name below assets/images/");
        }
        try {
            Path file = AtomicFiles.resolveContained(images, name);
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                return Result.rejected(path, "missing", name + " does not exist");
            }
            if (Files.size(file) > AssetImage.MAX_BYTES) {
                return Result.rejected(path, "size", "larger than " + AssetImage.MAX_BYTES + " bytes");
            }
            return AssetImage.read(path, Files.readAllBytes(file), maxWidth, maxHeight);
        } catch (IOException unsafe) {
            return Result.rejected(path, "path", "must be a plain file name below assets/images/");
        }
    }
}
