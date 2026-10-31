package com.siberanka.vibecore.menu;

import com.siberanka.vibecore.core.validation.MapReader;
import com.siberanka.vibecore.core.validation.Problem;
import com.siberanka.vibecore.core.validation.Result;
import com.siberanka.vibecore.logic.Action;
import com.siberanka.vibecore.logic.Condition;
import com.siberanka.vibecore.logic.LogicParser;
import com.siberanka.vibecore.project.Identifier;
import com.siberanka.vibecore.project.MigrationChain;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * Validates a menu document as a whole. Every defect is reported with its
 * path, and an invalid menu never replaces a working one.
 *
 * <pre>
 * schema-version: 1
 * title: "&lt;gold&gt;Shop"
 * rows: 3
 * permission: vibecore.menu.shop       # optional
 * open-condition: {compare: ...}       # optional
 * on-open: [...]   on-close: [...]     # optional action chains
 * items:
 *   coin:
 *     slots: [11, 12]                  # or slot: 11
 *     material: gold_ingot
 *     amount: 1
 *     name: "&lt;yellow&gt;Coin"
 *     lore: ["&lt;gray&gt;Costs 10"]
 *     glow: false
 *     visible: {compare: ...}          # optional
 *     states:                          # first matching state wins
 *       - when: {compare: ...}
 *         material: barrier
 *     actions:
 *       left: [...]                    # left, right, shift-left, shift-right, middle
 * </pre>
 */
public final class MenuParser {

    public static final MigrationChain SCHEMA = MigrationChain.of(1, List.of());
    public static final int MAX_ITEMS = 54;
    public static final int MAX_LORE_LINES = 32;
    public static final int MAX_TEXT = 512;

    private static final Pattern MATERIAL = Pattern.compile("[a-z0-9_]{1,64}");
    private static final Pattern PERMISSION = Pattern.compile("[a-z0-9_.-]{1,128}");
    private static final Set<String> ROOT_KEYS = Set.of(MigrationChain.VERSION_KEY, "title", "rows", "permission",
            "open-condition", "on-open", "on-close", "items");
    private static final Set<String> ITEM_KEYS = Set.of("slot", "slots", "material", "amount", "name", "lore", "glow",
            "visible", "states", "actions");
    private static final Set<String> STATE_KEYS = Set.of("when", "material", "amount", "name", "lore", "glow");

    private final Predicate<String> materialExists;
    private final List<Problem> problems = new ArrayList<>();

    private MenuParser(Predicate<String> materialExists) {
        this.materialExists = materialExists;
    }

    /**
     * Parses a menu document.
     *
     * @param id             identifier taken from the file location
     * @param materialExists checks that a lower-case material key names a usable item on this server
     */
    public static Result<MenuDefinition> parse(Identifier id, Map<String, ?> document,
                                               Predicate<String> materialExists) {
        Result<Map<String, Object>> migrated = SCHEMA.migrate(document);
        if (!migrated.isAccepted()) {
            return Result.rejected(migrated.problems());
        }
        MenuParser parser = new MenuParser(materialExists);
        MenuDefinition definition = parser.menu(id, migrated.orThrow());
        return parser.problems.isEmpty() ? Result.accepted(definition) : Result.rejected(parser.problems);
    }

    private MenuDefinition menu(Identifier id, Map<String, Object> document) {
        MapReader root = MapReader.root(document);
        root.rejectUnknownKeys(ROOT_KEYS);
        String title = root.text("title", "", MAX_TEXT);
        int rows = root.integer("rows", 3, 1, 6);
        String permission = root.text("permission", MAX_TEXT).orElse(null);
        if (permission != null && !PERMISSION.matcher(permission).matches()) {
            root.report("permission", "format", "permission must match [a-z0-9_.-]{1,128}");
        }
        problems.addAll(root.problems());
        Condition openCondition = optionalCondition("open-condition", document.get("open-condition"));
        List<Action> onOpen = chain("on-open", document.get("on-open"));
        List<Action> onClose = chain("on-close", document.get("on-close"));
        List<MenuItem> items = items(document.get("items"), rows * 9);
        return new MenuDefinition(id, title, rows, permission, openCondition, onOpen, onClose, items);
    }

    private List<Action> chain(String path, Object raw) {
        Result<List<Action>> parsed = LogicParser.actions(path, raw);
        problems.addAll(parsed.problems());
        if (!parsed.isAccepted()) {
            return List.of();
        }
        problems.addAll(MenuEffect.validate(path, parsed.orThrow()));
        return parsed.orThrow();
    }

    private Condition optionalCondition(String path, Object raw) {
        if (raw == null) {
            return null;
        }
        Result<Condition> parsed = LogicParser.condition(path, raw);
        problems.addAll(parsed.problems());
        return parsed.isAccepted() ? parsed.orThrow() : null;
    }

    private List<MenuItem> items(Object raw, int size) {
        if (raw == null) {
            return List.of();
        }
        if (!(raw instanceof Map<?, ?> section)) {
            problems.add(new Problem("items", "type", "expected a section of items"));
            return List.of();
        }
        if (section.size() > MAX_ITEMS) {
            problems.add(new Problem("items", "size-limit", "more than " + MAX_ITEMS + " items"));
            return List.of();
        }
        Map<Integer, String> owners = new HashMap<>();
        List<MenuItem> items = new ArrayList<>();
        for (Map.Entry<?, ?> entry : section.entrySet()) {
            String key = String.valueOf(entry.getKey());
            String path = "items." + key;
            if (!key.matches("[a-z0-9_-]{1,32}")) {
                problems.add(new Problem(path, "format", "item keys must match [a-z0-9_-]{1,32}"));
                continue;
            }
            MenuItem item = item(key, path, entry.getValue(), size);
            if (item == null) {
                continue;
            }
            for (int slot : item.slots()) {
                String previous = owners.putIfAbsent(slot, key);
                if (previous != null) {
                    problems.add(new Problem(path, "slot-conflict", "slot " + slot + " is already used by " + previous));
                }
            }
            items.add(item);
        }
        return items;
    }

    private MenuItem item(String key, String path, Object raw, int size) {
        if (!(raw instanceof Map<?, ?> rawSection)) {
            problems.add(new Problem(path, "type", "expected an item section"));
            return null;
        }
        Map<String, Object> section = copy(rawSection);
        MapReader reader = MapReader.root(section);
        reader.rejectUnknownKeys(ITEM_KEYS);
        prefix(path, reader.problems());
        List<Integer> slots = slots(path, section, size);
        ItemLook base = look(path, section, true);
        Condition visible = optionalCondition(path + ".visible", section.get("visible"));
        List<MenuItem.State> states = states(path + ".states", section.get("states"));
        Map<ClickChannel, List<Action>> actions = actions(path + ".actions", section.get("actions"));
        if (slots.isEmpty() || base == null) {
            return null;
        }
        return new MenuItem(key, slots, base, visible, states, actions);
    }

    private void prefix(String path, List<Problem> found) {
        for (Problem problem : found) {
            problems.add(new Problem(path + "." + problem.path(), problem.code(), problem.detail()));
        }
    }

    private static Map<String, Object> copy(Map<?, ?> source) {
        Map<String, Object> copy = new LinkedHashMap<>();
        source.forEach((key, value) -> copy.put(String.valueOf(key), value));
        return copy;
    }

    private List<Integer> slots(String path, Map<String, Object> section, int size) {
        Object single = section.get("slot");
        Object many = section.get("slots");
        if ((single == null) == (many == null)) {
            problems.add(new Problem(path, "slots", "set exactly one of slot or slots"));
            return List.of();
        }
        List<?> raw = single != null ? List.of(single) : many instanceof List<?> list ? list : null;
        if (raw == null || raw.isEmpty()) {
            problems.add(new Problem(path + ".slots", "type", "expected a non-empty list of slot numbers"));
            return List.of();
        }
        List<Integer> slots = new ArrayList<>();
        for (Object value : raw) {
            if (!(value instanceof Integer slot) || slot < 0 || slot >= size) {
                problems.add(new Problem(path + ".slots", "range", "slots must be whole numbers from 0 to " + (size - 1)));
                return List.of();
            }
            if (slots.contains(slot)) {
                problems.add(new Problem(path + ".slots", "duplicate", "slot " + slot + " is listed twice"));
                return List.of();
            }
            slots.add(slot);
        }
        return slots;
    }

    private ItemLook look(String path, Map<String, Object> section, boolean base) {
        int before = problems.size();
        String material = null;
        Object rawMaterial = section.get("material");
        if (rawMaterial != null || base) {
            if (!(rawMaterial instanceof String text) || !MATERIAL.matcher(text).matches()) {
                problems.add(new Problem(path + ".material", "format", "expected a lower-case material key"));
            } else if (!materialExists.test(text)) {
                problems.add(new Problem(path + ".material", "unknown-material", "'" + text + "' is not an item here"));
            } else {
                material = text;
            }
        }
        Integer amount = null;
        Object rawAmount = section.get("amount");
        if (rawAmount != null) {
            if (rawAmount instanceof Integer value && value >= 1 && value <= 99) {
                amount = value;
            } else {
                problems.add(new Problem(path + ".amount", "range", "amount must be a whole number from 1 to 99"));
            }
        } else if (base) {
            amount = 1;
        }
        String name = text(path + ".name", section.get("name"));
        List<String> lore = lore(path + ".lore", section.get("lore"));
        Boolean glow = null;
        Object rawGlow = section.get("glow");
        if (rawGlow instanceof Boolean flag) {
            glow = flag;
        } else if (rawGlow != null) {
            problems.add(new Problem(path + ".glow", "type", "expected true or false"));
        } else if (base) {
            glow = false;
        }
        if (problems.size() != before) {
            return null;
        }
        return new ItemLook(material, amount, name, base && lore == null ? List.of() : lore, glow);
    }

    private String text(String path, Object raw) {
        if (raw == null) {
            return null;
        }
        if (!(raw instanceof String text) || text.length() > MAX_TEXT) {
            problems.add(new Problem(path, "type", "expected text of at most " + MAX_TEXT + " characters"));
            return null;
        }
        return text;
    }

    private List<String> lore(String path, Object raw) {
        if (raw == null) {
            return null;
        }
        if (!(raw instanceof List<?> list) || list.size() > MAX_LORE_LINES) {
            problems.add(new Problem(path, "type", "expected at most " + MAX_LORE_LINES + " lines of text"));
            return null;
        }
        List<String> lines = new ArrayList<>();
        for (Object line : list) {
            String text = text(path, line);
            if (text == null) {
                return null;
            }
            lines.add(text);
        }
        return lines;
    }

    private List<MenuItem.State> states(String path, Object raw) {
        if (raw == null) {
            return List.of();
        }
        if (!(raw instanceof List<?> list) || list.size() > 16) {
            problems.add(new Problem(path, "type", "expected a list of at most 16 states"));
            return List.of();
        }
        List<MenuItem.State> states = new ArrayList<>();
        for (int index = 0; index < list.size(); index++) {
            String at = path + "[" + index + "]";
            if (!(list.get(index) instanceof Map<?, ?> rawState)) {
                problems.add(new Problem(at, "type", "expected a state section"));
                continue;
            }
            Map<String, Object> state = copy(rawState);
            MapReader reader = MapReader.root(state);
            reader.rejectUnknownKeys(STATE_KEYS);
            prefix(at, reader.problems());
            if (state.get("when") == null) {
                problems.add(new Problem(at + ".when", "missing", "a state needs a when condition"));
                continue;
            }
            Condition when = optionalCondition(at + ".when", state.get("when"));
            ItemLook look = look(at, state, false);
            if (when != null && look != null) {
                states.add(new MenuItem.State(when, look));
            }
        }
        return states;
    }

    private Map<ClickChannel, List<Action>> actions(String path, Object raw) {
        Map<ClickChannel, List<Action>> actions = new EnumMap<>(ClickChannel.class);
        if (raw == null) {
            return actions;
        }
        if (!(raw instanceof Map<?, ?> section)) {
            problems.add(new Problem(path, "type", "expected a section of click channels"));
            return actions;
        }
        section.forEach((rawKey, chain) -> {
            String key = String.valueOf(rawKey);
            var channel = ClickChannel.fromKey(key);
            if (channel.isEmpty()) {
                problems.add(new Problem(path + "." + key, "unknown-channel",
                        "use left, right, shift-left, shift-right or middle"));
                return;
            }
            actions.put(channel.get(), chain(path + "." + key, chain));
        });
        return actions;
    }
}
