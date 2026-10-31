package com.siberanka.vibecore.hologram;

import com.siberanka.vibecore.core.validation.MapReader;
import com.siberanka.vibecore.core.validation.Problem;
import com.siberanka.vibecore.core.validation.Result;
import com.siberanka.vibecore.logic.Condition;
import com.siberanka.vibecore.logic.LogicParser;
import com.siberanka.vibecore.logic.LogicReferences;
import com.siberanka.vibecore.project.DefinitionFiles;
import com.siberanka.vibecore.project.Identifier;
import com.siberanka.vibecore.project.MigrationChain;
import com.siberanka.vibecore.variable.VariableDefinition;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * Hologram designs from {@code holograms/}, validated against the declared
 * variables.
 *
 * <pre>
 * schema-version: 1
 * billboard: center          # center, vertical, horizontal or fixed
 * view-range: 24             # blocks, 4 to 64
 * scale: 1.0                 # 0.25 to 4
 * shadow: true
 * background: "#40000000"    # ARGB, or omit for the default
 * visible: {compare: ...}    # optional per-viewer condition
 * slides:
 *   - seconds: 5
 *     lines: ["&lt;gold&gt;Welcome, &lt;player&gt;", "&lt;gray&gt;Online: &lt;data:online&gt;"]
 *   - seconds: 5
 *     lines: ["&lt;aqua&gt;Clicks: &lt;var:example/clicks&gt;"]
 * </pre>
 */
public record HologramCatalog(Map<Identifier, HologramDesign> designs) {

    public static final MigrationChain SCHEMA = MigrationChain.of(1, List.of());
    private static final int MAX_SLIDES = 16;
    private static final int MAX_LINES = 16;
    private static final int MAX_TEXT = 256;

    public HologramCatalog {
        designs = Map.copyOf(designs);
    }

    public Optional<HologramDesign> design(Identifier id) {
        return Optional.ofNullable(designs.get(id));
    }

    public static Result<HologramCatalog> load(Path dataFolder, Map<Identifier, VariableDefinition> variables) {
        List<Problem> problems = new ArrayList<>();
        Map<Identifier, HologramDesign> designs = new TreeMap<>();
        Set<Identifier> used = new HashSet<>();
        for (var entry : DefinitionFiles.read(dataFolder.resolve("holograms"), "holograms", problems).entrySet()) {
            List<Problem> found = new ArrayList<>();
            HologramDesign design = design(entry.getKey(), entry.getValue(), found, used);
            problems.addAll(DefinitionFiles.label("holograms", entry.getKey(), found));
            if (design != null) {
                designs.put(entry.getKey(), design);
            }
        }
        used.stream().filter(key -> !variables.containsKey(key)).sorted().forEach(key -> problems.add(
                new Problem("holograms", "unknown-variable", key + " is not declared in variables.yml")));
        return problems.isEmpty() ? Result.accepted(new HologramCatalog(designs)) : Result.rejected(problems);
    }

    private static HologramDesign design(Identifier id, Map<String, Object> raw, List<Problem> problems,
                                         Set<Identifier> used) {
        Result<Map<String, Object>> migrated = SCHEMA.migrate(raw);
        problems.addAll(migrated.problems());
        if (!migrated.isAccepted()) {
            return null;
        }
        Map<String, Object> document = migrated.orThrow();
        MapReader reader = MapReader.root(document);
        reader.rejectUnknownKeys(Set.of(MigrationChain.VERSION_KEY, "billboard", "view-range", "scale", "shadow",
                "background", "visible", "slides"));
        String billboardKey = reader.text("billboard", "center", 16);
        HologramDesign.Billboard billboard = null;
        try {
            billboard = HologramDesign.Billboard.valueOf(billboardKey.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            reader.report("billboard", "format", "use center, vertical, horizontal or fixed");
        }
        double range = reader.number("view-range", 24, 4, 64);
        double scale = reader.number("scale", 1, 0.25, 4);
        boolean shadow = reader.bool("shadow", false);
        Integer background = null;
        String backgroundText = reader.text("background", 16).orElse(null);
        if (backgroundText != null) {
            if (backgroundText.matches("#[0-9a-fA-F]{8}")) {
                background = (int) Long.parseLong(backgroundText.substring(1), 16);
            } else {
                reader.report("background", "format", "expected an ARGB colour such as #40000000");
            }
        }
        Condition visible = null;
        if (document.get("visible") != null) {
            Result<Condition> parsed = LogicParser.condition("visible", document.get("visible"));
            problems.addAll(parsed.problems());
            visible = parsed.isAccepted() ? parsed.orThrow() : null;
            LogicReferences.collect(visible, used, new HashSet<>());
        }
        List<HologramDesign.Slide> slides = slides(document.get("slides"), problems, used);
        problems.addAll(reader.problems());
        if (!problems.isEmpty()) {
            return null;
        }
        return new HologramDesign(id, slides, billboard, range, (float) scale, shadow, background, visible);
    }

    private static List<HologramDesign.Slide> slides(Object raw, List<Problem> problems, Set<Identifier> used) {
        if (!(raw instanceof List<?> list) || list.isEmpty() || list.size() > MAX_SLIDES) {
            problems.add(new Problem("slides", "type", "expected 1 to " + MAX_SLIDES + " slides"));
            return List.of();
        }
        List<HologramDesign.Slide> slides = new ArrayList<>();
        for (int index = 0; index < list.size(); index++) {
            String path = "slides[" + index + "]";
            if (!(list.get(index) instanceof Map<?, ?> section)) {
                problems.add(new Problem(path, "type", "expected a slide section"));
                continue;
            }
            Map<String, Object> copy = new java.util.LinkedHashMap<>();
            section.forEach((key, value) -> copy.put(String.valueOf(key), value));
            MapReader reader = MapReader.root(copy);
            reader.rejectUnknownKeys(Set.of("seconds", "lines"));
            double seconds = reader.number("seconds", 5, 0.5, 3600);
            List<String> lines = new ArrayList<>();
            if (!(copy.get("lines") instanceof List<?> rawLines) || rawLines.isEmpty() || rawLines.size() > MAX_LINES) {
                problems.add(new Problem(path + ".lines", "type", "expected 1 to " + MAX_LINES + " lines"));
            } else {
                for (Object line : rawLines) {
                    if (line instanceof String text && text.length() <= MAX_TEXT) {
                        lines.add(text);
                        variables(text, used);
                    } else {
                        problems.add(new Problem(path + ".lines", "type",
                                "lines must be text of at most " + MAX_TEXT + " characters"));
                    }
                }
            }
            reader.problems().forEach(problem ->
                    problems.add(new Problem(path + "." + problem.path(), problem.code(), problem.detail())));
            slides.add(new HologramDesign.Slide(lines, Math.round(seconds * 1000)));
        }
        return slides;
    }

    /** Records {@code <var:key>} references of a text line for validation. */
    private static void variables(String text, Set<Identifier> used) {
        var matcher = java.util.regex.Pattern.compile("<var:([a-z0-9_.:/-]+)>").matcher(text);
        while (matcher.find()) {
            Identifier.parse("var", matcher.group(1)).map(used::add);
        }
    }
}
