package com.siberanka.vibecore.hud;

import com.siberanka.vibecore.core.validation.MapReader;
import com.siberanka.vibecore.core.validation.Problem;
import com.siberanka.vibecore.core.validation.Result;
import com.siberanka.vibecore.logic.Condition;
import com.siberanka.vibecore.logic.LogicParser;
import com.siberanka.vibecore.logic.LogicReferences;
import com.siberanka.vibecore.logic.Operand;
import com.siberanka.vibecore.project.DefinitionFiles;
import com.siberanka.vibecore.project.Identifier;
import com.siberanka.vibecore.project.MigrationChain;
import com.siberanka.vibecore.variable.VariableDefinition;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Predicate;

/**
 * HUDs from {@code huds/} and popups from {@code popups/}, validated together
 * against the declared variables.
 *
 * <pre>
 * # huds/main.yml                       # popups/coins.yml
 * schema-version: 1                     schema-version: 1
 * priority: 10                          surface: title     # or actionbar
 * when: {compare: ...}                  text: "&lt;gold&gt;+10 coins"
 * refresh-ticks: 20                     subtitle: "&lt;gray&gt;Daily reward"
 * actionbar: "&lt;glyph:heart&gt; &lt;data:health&gt;"     seconds: 3
 * bossbar:
 *   text: "&lt;var:quest/name&gt;"
 *   color: purple                       # pink, blue, red, green, yellow, purple, white
 *   overlay: progress                   # progress, notched_6, notched_10, notched_12, notched_20
 *   value: {var: quest/done}
 *   max: 10
 * </pre>
 */
public record HudCatalog(List<HudDefinition> huds, Map<Identifier, PopupDefinition> popups) {

    public static final MigrationChain SCHEMA = MigrationChain.of(1, List.of());
    private static final int MAX_TEXT = 512;
    private static final Set<String> COLORS = Set.of("pink", "blue", "red", "green", "yellow", "purple", "white");
    private static final Set<String> OVERLAYS = Set.of("progress", "notched_6", "notched_10", "notched_12",
            "notched_20");

    public HudCatalog {
        huds = huds.stream().sorted(Comparator.comparingInt(HudDefinition::priority).reversed()
                .thenComparing(HudDefinition::id)).toList();
        popups = Map.copyOf(popups);
    }

    public Optional<HudDefinition> hud(Identifier id) {
        return huds.stream().filter(hud -> hud.id().equals(id)).findFirst();
    }

    public static Result<HudCatalog> load(Path dataFolder, Map<Identifier, VariableDefinition> variables,
                                          Predicate<String> materialExists) {
        List<Problem> problems = new ArrayList<>();
        List<HudDefinition> huds = new ArrayList<>();
        Map<Identifier, PopupDefinition> popups = new TreeMap<>();
        Set<Identifier> usedVariables = new HashSet<>();
        Set<String> usedMaterials = new HashSet<>();
        for (var entry : DefinitionFiles.read(dataFolder.resolve("huds"), "huds", problems).entrySet()) {
            List<Problem> found = new ArrayList<>();
            HudDefinition hud = hud(entry.getKey(), entry.getValue(), found, usedVariables, usedMaterials);
            problems.addAll(DefinitionFiles.label("huds", entry.getKey(), found));
            if (hud != null && found.isEmpty()) {
                huds.add(hud);
            }
        }
        for (var entry : DefinitionFiles.read(dataFolder.resolve("popups"), "popups", problems).entrySet()) {
            List<Problem> found = new ArrayList<>();
            PopupDefinition popup = popup(entry.getKey(), entry.getValue(), found);
            problems.addAll(DefinitionFiles.label("popups", entry.getKey(), found));
            if (popup != null && found.isEmpty()) {
                popups.put(entry.getKey(), popup);
            }
        }
        usedVariables.stream().filter(key -> !variables.containsKey(key)).sorted().forEach(key ->
                problems.add(new Problem("huds", "unknown-variable", key + " is not declared in variables.yml")));
        usedMaterials.stream().filter(key -> !materialExists.test(key)).sorted().forEach(key ->
                problems.add(new Problem("huds", "unknown-material", "'" + key + "' is not an item here")));
        return problems.isEmpty() ? Result.accepted(new HudCatalog(huds, popups)) : Result.rejected(problems);
    }

    private static Map<String, Object> migrated(Map<String, Object> document, List<Problem> problems) {
        Result<Map<String, Object>> result = SCHEMA.migrate(document);
        problems.addAll(result.problems());
        return result.isAccepted() ? result.orThrow() : null;
    }

    private static HudDefinition hud(Identifier id, Map<String, Object> raw, List<Problem> problems,
                                     Set<Identifier> variables, Set<String> materials) {
        Map<String, Object> document = migrated(raw, problems);
        if (document == null) {
            return null;
        }
        MapReader reader = MapReader.root(document);
        reader.rejectUnknownKeys(Set.of(MigrationChain.VERSION_KEY, "priority", "when", "refresh-ticks",
                "actionbar", "bossbar"));
        int priority = reader.integer("priority", 0, -1000, 1000);
        int refresh = reader.integer("refresh-ticks", 20, 5, 200);
        String actionbar = reader.text("actionbar", MAX_TEXT).orElse(null);
        Condition when = null;
        if (document.get("when") != null) {
            Result<Condition> parsed = LogicParser.condition("when", document.get("when"));
            problems.addAll(parsed.problems());
            when = parsed.isAccepted() ? parsed.orThrow() : null;
            LogicReferences.collect(when, variables, materials);
        }
        HudDefinition.Bar bar = null;
        if (document.get("bossbar") != null) {
            MapReader section = reader.section("bossbar");
            section.rejectUnknownKeys(Set.of("text", "color", "overlay", "value", "max"));
            String text = section.text("text", "", MAX_TEXT);
            String color = section.text("color", "white", 16);
            String overlay = section.text("overlay", "progress", 16);
            if (!COLORS.contains(color)) {
                section.report("color", "format", "use one of " + new java.util.TreeSet<>(COLORS));
            }
            if (!OVERLAYS.contains(overlay)) {
                section.report("overlay", "format", "use progress, notched_6, notched_10, notched_12 or notched_20");
            }
            Map<?, ?> fields = document.get("bossbar") instanceof Map<?, ?> map ? map : Map.of();
            Operand value = operand("bossbar.value", fields.get("value"), 1, problems, variables);
            Operand max = operand("bossbar.max", fields.get("max"), 1, problems, variables);
            bar = new HudDefinition.Bar(text, color, overlay, value, max);
        }
        problems.addAll(reader.problems());
        if (actionbar == null && bar == null) {
            problems.add(new Problem("", "empty", "a HUD needs an actionbar, a bossbar or both"));
        }
        return problems.isEmpty() ? new HudDefinition(id, priority, when, refresh, actionbar, bar) : null;
    }

    private static Operand operand(String path, Object raw, double fallback, List<Problem> problems,
                                   Set<Identifier> variables) {
        Result<Operand> parsed = LogicParser.operand(path, raw == null ? fallback : raw);
        problems.addAll(parsed.problems());
        if (!parsed.isAccepted()) {
            return null;
        }
        LogicReferences.collect(parsed.orThrow(), variables);
        return parsed.orThrow();
    }

    private static PopupDefinition popup(Identifier id, Map<String, Object> raw, List<Problem> problems) {
        Map<String, Object> document = migrated(raw, problems);
        if (document == null) {
            return null;
        }
        MapReader reader = MapReader.root(document);
        reader.rejectUnknownKeys(Set.of(MigrationChain.VERSION_KEY, "surface", "text", "subtitle", "seconds"));
        String surface = reader.text("surface", "title", 16);
        String text = reader.text("text", "", MAX_TEXT);
        String subtitle = reader.text("subtitle", MAX_TEXT).orElse(null);
        double seconds = reader.number("seconds", 3, PopupDefinition.MIN_MILLIS / 1000.0,
                PopupDefinition.MAX_MILLIS / 1000.0);
        if (!surface.equals("title") && !surface.equals("actionbar")) {
            reader.report("surface", "format", "use title or actionbar");
        }
        if (text.isEmpty()) {
            reader.report("text", "missing", "a popup needs text");
        }
        if (subtitle != null && surface.equals("actionbar")) {
            reader.report("subtitle", "format", "only title popups have a subtitle");
        }
        problems.addAll(reader.problems());
        if (!problems.isEmpty()) {
            return null;
        }
        return new PopupDefinition(id, surface.equals("title") ? PopupDefinition.Surface.TITLE
                : PopupDefinition.Surface.ACTIONBAR, text, subtitle, Math.round(seconds * 1000));
    }
}
