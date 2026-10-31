package com.siberanka.vibecore.menu;

import com.siberanka.vibecore.core.validation.Problem;
import com.siberanka.vibecore.logic.Action;
import com.siberanka.vibecore.logic.LogicParser;
import com.siberanka.vibecore.project.Identifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Effects a menu chain may emit, performed only after the chain committed.
 *
 * <pre>
 * emit: {type: message, text: "&lt;green&gt;Bought &lt;var:coins&gt;"}
 * emit: {type: sound, sound: entity.experience_orb.pickup, volume: 1, pitch: 1.2}
 * emit: {type: open, menu: shop/page-2}
 * emit: {type: close}
 * emit: {type: refresh}
 * emit: {type: money-take, amount: 10.5}        # needs an economy provider
 * emit: {type: money-give, amount: 10}
 * emit: {type: item-take, material: diamond, amount: 3}   # plain items only
 * emit: {type: item-give, material: diamond, amount: 3}
 * emit: {type: command, as: console, run: "give <player> diamond 1"}   # see CommandTemplate
 * emit: {type: input, var: nick, prompt: "<yellow>Type a name", timeout: 30}   # chat input, then reopen
 * </pre>
 */
public final class MenuEffect {

    private static final Map<String, Set<String>> ARGUMENTS = Map.ofEntries(
            Map.entry("message", Set.of("text")),
            Map.entry("sound", Set.of("sound", "volume", "pitch")),
            Map.entry("open", Set.of("menu")),
            Map.entry("close", Set.of()),
            Map.entry("refresh", Set.of()),
            Map.entry("money-take", Set.of("amount")),
            Map.entry("money-give", Set.of("amount")),
            Map.entry("item-take", Set.of("material", "amount")),
            Map.entry("item-give", Set.of("material", "amount")),
            Map.entry("command", Set.of("as", "run")),
            Map.entry("input", Set.of("var", "prompt", "timeout")));

    /** Effects performed together with the variable changes, all or none. */
    public static final Set<String> TRANSACTIONAL = Set.of("money-take", "money-give", "item-take", "item-give");

    private MenuEffect() {
    }

    /** Checks every emit in the chain, including nested branches. */
    public static List<Problem> validate(String path, List<Action> chain) {
        List<Problem> problems = new ArrayList<>();
        walk(path, chain, problems);
        return problems;
    }

    private static void walk(String path, List<Action> chain, List<Problem> problems) {
        for (int index = 0; index < chain.size(); index++) {
            String at = path + "[" + index + "]";
            switch (chain.get(index)) {
                case Action.Emit emit -> check(at + ".emit", emit, problems);
                case Action.If branch -> {
                    walk(at + ".if.then", branch.then(), problems);
                    walk(at + ".if.else", branch.otherwise(), problems);
                }
                default -> {
                }
            }
        }
    }

    private static void check(String path, Action.Emit emit, List<Problem> problems) {
        Set<String> allowed = ARGUMENTS.get(emit.type());
        if (allowed == null) {
            problems.add(new Problem(path + ".type", "unknown-effect", "use message, sound, open, close, refresh,"
                    + " money-take, money-give, item-take, item-give, command or input"));
            return;
        }
        for (String name : emit.arguments().keySet()) {
            if (!allowed.contains(name)) {
                problems.add(new Problem(path + "." + name, "unknown-key", "not an argument of " + emit.type()));
            }
        }
        Map<String, String> arguments = emit.arguments();
        switch (emit.type()) {
            case "message" -> {
                if (arguments.getOrDefault("text", "").isEmpty()) {
                    problems.add(new Problem(path + ".text", "missing", "a message needs text"));
                }
            }
            case "sound" -> {
                String sound = arguments.getOrDefault("sound", "");
                if (!sound.matches("([a-z0-9_.-]+:)?[a-z0-9_./-]+")) {
                    problems.add(new Problem(path + ".sound", "format", "expected a sound key such as ui.button.click"));
                }
                range(path + ".volume", arguments.get("volume"), 0, 4, problems);
                range(path + ".pitch", arguments.get("pitch"), 0.5, 2, problems);
            }
            case "input" -> {
                if (!Identifier.parse(path + ".var", arguments.get("var")).isAccepted()) {
                    problems.add(new Problem(path + ".var", "format", "expected a variable identifier"));
                }
                if (arguments.getOrDefault("prompt", "").isEmpty()) {
                    problems.add(new Problem(path + ".prompt", "missing", "an input needs a prompt"));
                }
                range(path + ".timeout", arguments.getOrDefault("timeout", "30"), 5, 300, problems);
            }
            case "command" -> {
                if (!Set.of("console", "player").contains(arguments.getOrDefault("as", ""))) {
                    problems.add(new Problem(path + ".as", "format", "expected console or player"));
                }
                problems.addAll(CommandTemplate.parse(path + ".run", arguments.get("run")).problems());
            }
            case "money-take", "money-give" -> {
                if (LogicParser.parseMoney(arguments.get("amount")) == null) {
                    problems.add(new Problem(path + ".amount", "range",
                            "expected a positive amount with at most two decimals"));
                }
            }
            case "item-take", "item-give" -> {
                if (!arguments.getOrDefault("material", "").matches("[a-z0-9_]{1,64}")) {
                    problems.add(new Problem(path + ".material", "format", "expected a lower-case material key"));
                }
                range(path + ".amount", arguments.getOrDefault("amount", "1"), 1, 2304, problems);
                if (!arguments.getOrDefault("amount", "1").matches("[0-9]{1,4}")) {
                    problems.add(new Problem(path + ".amount", "type", "expected a whole number"));
                }
            }
            case "open" -> {
                if (!Identifier.parse(path + ".menu", arguments.get("menu")).isAccepted()) {
                    problems.add(new Problem(path + ".menu", "format", "expected a menu identifier"));
                }
            }
            default -> {
            }
        }
    }

    private static void range(String path, String raw, double min, double max, List<Problem> problems) {
        if (raw == null) {
            return;
        }
        try {
            double value = Double.parseDouble(raw);
            if (value >= min && value <= max) {
                return;
            }
        } catch (NumberFormatException ignored) {
            // reported below
        }
        problems.add(new Problem(path, "range", "must be a number from " + min + " to " + max));
    }

    public static float number(String raw, float fallback) {
        return raw == null ? fallback : Float.parseFloat(raw);
    }
}
