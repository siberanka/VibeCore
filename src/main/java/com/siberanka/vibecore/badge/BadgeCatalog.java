package com.siberanka.vibecore.badge;

import com.siberanka.vibecore.core.validation.MapReader;
import com.siberanka.vibecore.core.validation.Problem;
import com.siberanka.vibecore.core.validation.Result;
import com.siberanka.vibecore.project.DefinitionFiles;
import com.siberanka.vibecore.project.Identifier;
import com.siberanka.vibecore.project.MigrationChain;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import net.kyori.adventure.text.minimessage.MiniMessage;

/**
 * Rank badges from {@code badges/}. A badge names a glyph of the resource
 * pack; it is only a picture. It never grants a permission or a group, and
 * where it is shown (chat, tab list, name tags) stays the job of the plugins
 * that own those places.
 *
 * <pre>
 * schema-version: 1
 * glyph: vip                    # a glyph id from assets/pack.yml
 * name: "&lt;gold&gt;VIP"
 * description: "&lt;gray&gt;Supporters of the server"
 * </pre>
 */
public record BadgeCatalog(Map<Identifier, Badge> badges) {

    public static final MigrationChain SCHEMA = MigrationChain.of(1, List.of());
    private static final int MAX_TEXT = 256;

    /** One badge. */
    public record Badge(Identifier id, String glyph, String name, String description) {
    }

    public BadgeCatalog {
        badges = Map.copyOf(badges);
    }

    public Optional<Badge> badge(Identifier id) {
        return Optional.ofNullable(badges.get(id));
    }

    /** Badges whose id or name contains {@code query}, case-insensitively, in id order. */
    public List<Badge> search(String query) {
        String needle = fold(query);
        return new TreeMap<>(badges).values().stream()
                .filter(badge -> badge.id().toString().contains(needle)
                        || fold(MiniMessage.miniMessage().stripTags(badge.name())).contains(needle))
                .toList();
    }

    /**
     * Case folding that treats Turkish dotted and dotless i like i, so "iyi",
     * "İYİ" and "IYI" all find each other regardless of the server locale.
     */
    static String fold(String text) {
        return text.toLowerCase(Locale.ROOT).replace("̇", "").replace('ı', 'i');
    }

    /** Loads badges; {@code glyphs} are the glyph ids declared in the pack source. */
    public static Result<BadgeCatalog> load(Path dataFolder, Set<String> glyphs) {
        List<Problem> problems = new ArrayList<>();
        Map<Identifier, Badge> badges = new TreeMap<>();
        for (var entry : DefinitionFiles.read(dataFolder.resolve("badges"), "badges", problems).entrySet()) {
            List<Problem> found = new ArrayList<>();
            Result<Map<String, Object>> migrated = SCHEMA.migrate(entry.getValue());
            found.addAll(migrated.problems());
            if (migrated.isAccepted()) {
                MapReader reader = MapReader.root(migrated.orThrow());
                reader.rejectUnknownKeys(Set.of(MigrationChain.VERSION_KEY, "glyph", "name", "description"));
                String glyph = reader.text("glyph", "", 32);
                String name = reader.text("name", entry.getKey().path(), MAX_TEXT);
                String description = reader.text("description", "", MAX_TEXT);
                if (!glyphs.contains(glyph)) {
                    reader.report("glyph", "unknown-glyph", "'" + glyph + "' is not a glyph in assets/pack.yml");
                }
                found.addAll(reader.problems());
                if (found.isEmpty()) {
                    badges.put(entry.getKey(), new Badge(entry.getKey(), glyph, name, description));
                }
            }
            problems.addAll(DefinitionFiles.label("badges", entry.getKey(), found));
        }
        return problems.isEmpty() ? Result.accepted(new BadgeCatalog(badges)) : Result.rejected(problems);
    }
}
