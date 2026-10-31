package com.siberanka.vibecore.variable;

import com.siberanka.vibecore.core.validation.MapReader;
import com.siberanka.vibecore.core.validation.Result;
import com.siberanka.vibecore.project.Identifier;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Declared variable: key, type, scope, default and bounds. Every write is
 * validated against the definition; a definition never widens silently.
 */
public record VariableDefinition(
        Identifier key,
        VariableType type,
        VariableScope scope,
        VariableValue defaultValue,
        double min,
        double max,
        int maxLength) {

    /** Hard ceiling for text values regardless of configuration. */
    public static final int TEXT_LIMIT = 1024;

    /** Bounds of number variables; whole numbers up to this size stay exact. */
    public static final double NUMBER_LIMIT = 1e15;

    private static final Set<String> KEYS = Set.of("key", "type", "scope", "default", "min", "max", "max-length");

    public VariableDefinition {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(defaultValue, "defaultValue");
        if (!Double.isFinite(min) || !Double.isFinite(max) || min > max) {
            throw new IllegalArgumentException("Invalid numeric bounds");
        }
        if (maxLength < 0 || maxLength > TEXT_LIMIT) {
            throw new IllegalArgumentException("maxLength must be between 0 and " + TEXT_LIMIT);
        }
        if (defaultValue.type() != type) {
            throw new IllegalArgumentException("Default value type does not match " + type.key());
        }
    }

    /** Checks a candidate value against type and bounds. */
    public Result<VariableValue> check(VariableValue value) {
        String field = key.toString();
        if (value.type() != type) {
            return Result.rejected(field, "type", "expected " + type.key() + " but got " + value.type().key());
        }
        if (value instanceof VariableValue.NumberValue number && (number.value() < min || number.value() > max)) {
            return Result.rejected(field, "range", "must be between " + min + " and " + max);
        }
        if (value instanceof VariableValue.TextValue text) {
            int length = text.value().codePointCount(0, text.value().length());
            if (length > maxLength) {
                return Result.rejected(field, "length", "must not exceed " + maxLength + " characters");
            }
            if (containsControl(text.value())) {
                return Result.rejected(field, "control", "must not contain control characters");
            }
        }
        return Result.accepted(value);
    }

    private static boolean containsControl(String text) {
        return text.codePoints().anyMatch(codePoint -> Character.getType(codePoint) == Character.CONTROL);
    }

    /** Parses an untyped string, such as a command argument, into this variable's type. */
    public Result<VariableValue> parse(String input) {
        String field = key.toString();
        return switch (type) {
            case NUMBER -> {
                double parsed;
                try {
                    parsed = Double.parseDouble(input.trim());
                } catch (NumberFormatException invalid) {
                    yield Result.rejected(field, "type", "expected a number");
                }
                if (!Double.isFinite(parsed) || input.trim().matches("(?i).*[a-z].*")) {
                    yield Result.rejected(field, "type", "expected a plain finite number");
                }
                yield check(VariableValue.number(parsed));
            }
            case BOOLEAN -> switch (input.trim()) {
                case "true" -> Result.accepted(VariableValue.bool(true));
                case "false" -> Result.accepted(VariableValue.bool(false));
                default -> Result.rejected(field, "type", "expected true or false");
            };
            case TEXT -> check(VariableValue.text(input));
        };
    }

    /** Reads a definition from a project document section. */
    public static Result<VariableDefinition> read(Map<String, ?> section) {
        MapReader reader = MapReader.root(section);
        reader.rejectUnknownKeys(KEYS);
        Result<Identifier> key = Identifier.parse("key", reader.text("key", "", 200));
        String typeKey = reader.text("type", "", 16);
        String scopeKey = reader.text("scope", "", 32);
        VariableType type = VariableType.fromKey(typeKey).orElse(null);
        VariableScope scope = VariableScope.fromKey(scopeKey).orElse(null);
        if (type == null) {
            reader.report("type", "unknown-type", "must be number, boolean or text");
        }
        if (scope == null) {
            reader.report("scope", "unknown-scope",
                    "must be session, online-player, persistent-player or persistent-global");
        }
        double minimum = reader.number("min", -NUMBER_LIMIT, -NUMBER_LIMIT, NUMBER_LIMIT);
        double maximum = reader.number("max", NUMBER_LIMIT, -NUMBER_LIMIT, NUMBER_LIMIT);
        int maxLength = reader.integer("max-length", 256, 0, TEXT_LIMIT);
        if (minimum > maximum) {
            reader.report("min", "range", "must not be greater than max");
        }
        Object rawDefault = section.get("default");
        if (reader.hasProblems() || !key.isAccepted()) {
            var problems = new java.util.ArrayList<>(reader.problems());
            problems.addAll(key.problems());
            return Result.rejected(problems);
        }
        VariableValue defaultValue = switch (type) {
            case NUMBER -> rawDefault instanceof Number number && Double.isFinite(number.doubleValue())
                    ? VariableValue.number(number.doubleValue()) : null;
            case BOOLEAN -> rawDefault instanceof Boolean flag ? VariableValue.bool(flag) : null;
            case TEXT -> rawDefault instanceof String text ? VariableValue.text(text) : null;
        };
        if (rawDefault == null) {
            defaultValue = switch (type) {
                case NUMBER -> VariableValue.number(Math.max(minimum, Math.min(0, maximum)));
                case BOOLEAN -> VariableValue.bool(false);
                case TEXT -> VariableValue.text("");
            };
        }
        if (defaultValue == null) {
            return Result.rejected("default", "type", "default must be a " + type.key());
        }
        VariableDefinition definition = new VariableDefinition(key.orThrow(), type, scope, defaultValue,
                minimum, maximum, maxLength);
        Result<VariableValue> defaultCheck = definition.check(defaultValue);
        if (!defaultCheck.isAccepted()) {
            return Result.rejected(defaultCheck.problems());
        }
        return Result.accepted(definition);
    }
}
