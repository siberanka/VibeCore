package com.siberanka.vibecore.menu;

import com.siberanka.vibecore.core.validation.Problem;
import com.siberanka.vibecore.core.validation.Result;
import com.siberanka.vibecore.project.Identifier;
import com.siberanka.vibecore.variable.VariableValue;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A command written by the server owner in a menu file, with typed
 * placeholders: {@code <player>}, {@code <uuid>} and {@code <var:key>} for
 * number and boolean variables. Text variables are never inserted, so player
 * input can never become command syntax.
 */
public record CommandTemplate(String root, List<Part> parts) {

    /** Commands that grant or revoke operator status or stop the server are never allowed. */
    public static final Set<String> FORBIDDEN = Set.of("op", "deop", "stop", "restart", "reload", "rl",
            "minecraft:op", "minecraft:deop", "minecraft:stop", "minecraft:reload", "bukkit:reload", "bukkit:rl");

    private static final int MAX_LENGTH = 256;
    private static final Pattern PLACEHOLDER = Pattern.compile("<([a-z]+)(?::([a-z0-9_.:/-]+))?>");
    private static final Pattern PLAYER_NAME = Pattern.compile("[A-Za-z0-9_.*]{1,32}");

    /** Literal text or a placeholder. */
    public sealed interface Part permits Literal, PlayerName, PlayerId, Variable {
    }

    public record Literal(String text) implements Part {
    }

    public record PlayerName() implements Part {
    }

    public record PlayerId() implements Part {
    }

    public record Variable(Identifier key) implements Part {
    }

    public CommandTemplate {
        parts = List.copyOf(parts);
    }

    public static Result<CommandTemplate> parse(String path, String text) {
        if (text == null || text.isBlank() || text.length() > MAX_LENGTH) {
            return Result.rejected(path, "length", "expected a command of 1 to " + MAX_LENGTH + " characters");
        }
        if (text.startsWith("/")) {
            return Result.rejected(path, "format", "write the command without a leading slash");
        }
        if (text.codePoints().anyMatch(Character::isISOControl)) {
            return Result.rejected(path, "format", "commands must be a single line without control characters");
        }
        List<Part> parts = new ArrayList<>();
        Matcher matcher = PLACEHOLDER.matcher(text);
        int position = 0;
        while (matcher.find()) {
            String literal = text.substring(position, matcher.start());
            if (literal.indexOf('<') >= 0 || literal.indexOf('>') >= 0) {
                return Result.rejected(path, "placeholder", "only <player>, <uuid> and <var:key> are allowed");
            }
            if (!literal.isEmpty()) {
                parts.add(new Literal(literal));
            }
            String name = matcher.group(1);
            String argument = matcher.group(2);
            switch (name) {
                case "player" -> parts.add(new PlayerName());
                case "uuid" -> parts.add(new PlayerId());
                case "var" -> {
                    Result<Identifier> key = Identifier.parse(path, argument);
                    if (!key.isAccepted()) {
                        return Result.rejected(key.problems());
                    }
                    parts.add(new Variable(key.orThrow()));
                }
                default -> {
                    return Result.rejected(path, "placeholder", "unknown placeholder <" + name + ">");
                }
            }
            if (!name.equals("var") && argument != null) {
                return Result.rejected(path, "placeholder", "<" + name + "> takes no argument");
            }
            position = matcher.end();
        }
        String tail = text.substring(position);
        if (tail.indexOf('<') >= 0 || tail.indexOf('>') >= 0) {
            return Result.rejected(path, "placeholder", "only <player>, <uuid> and <var:key> are allowed");
        }
        if (!tail.isEmpty()) {
            parts.add(new Literal(tail));
        }
        if (!(parts.getFirst() instanceof Literal first) || first.text().isBlank()
                || Character.isWhitespace(first.text().charAt(0))) {
            return Result.rejected(path, "format", "a command must start with its name");
        }
        String root = first.text().trim().split("\\s+", 2)[0].toLowerCase(Locale.ROOT);
        if (FORBIDDEN.contains(root)) {
            return Result.rejected(path, "forbidden", "'" + root + "' can never run from a menu");
        }
        return Result.accepted(new CommandTemplate(root, parts));
    }

    /** Checks the command against the owner's allow list. */
    public Optional<Problem> checkAllowed(String path, Set<String> allowed) {
        return allowed.contains(root) ? Optional.empty()
                : Optional.of(new Problem(path, "not-allowed",
                "'" + root + "' is not listed in actions.allowed-commands of config.yml"));
    }

    public List<Identifier> variables() {
        return parts.stream().filter(Variable.class::isInstance).map(part -> ((Variable) part).key()).toList();
    }

    /** Builds the command line for a viewer; rejects names or values that could change its syntax. */
    public Result<String> render(String playerName, UUID playerId, Function<Identifier, Result<VariableValue>> variables) {
        StringBuilder line = new StringBuilder();
        for (Part part : parts) {
            switch (part) {
                case Literal literal -> line.append(literal.text());
                case PlayerName ignored -> {
                    if (!PLAYER_NAME.matcher(playerName).matches()) {
                        return Result.rejected("player", "unsafe-name", "player name cannot be used in a command");
                    }
                    line.append(playerName);
                }
                case PlayerId ignored -> line.append(playerId);
                case Variable variable -> {
                    Result<VariableValue> value = variables.apply(variable.key());
                    if (!value.isAccepted()) {
                        return Result.rejected(value.problems());
                    }
                    if (value.orThrow() instanceof VariableValue.TextValue) {
                        return Result.rejected(variable.key().toString(), "type",
                                "text variables cannot be inserted into commands");
                    }
                    line.append(value.orThrow().canonical());
                }
            }
        }
        return Result.accepted(line.toString());
    }
}
