package com.siberanka.vibecore.menu;

import com.siberanka.vibecore.core.validation.Problem;
import com.siberanka.vibecore.logic.Action;
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
 * </pre>
 */
public final class MenuEffect {

    private static final Map<String, Set<String>> ARGUMENTS = Map.of(
            "message", Set.of("text"),
            "sound", Set.of("sound", "volume", "pitch"),
            "open", Set.of("menu"),
            "close", Set.of(),
            "refresh", Set.of());

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
            problems.add(new Problem(path + ".type", "unknown-effect", "use message, sound, open, close or refresh"));
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
