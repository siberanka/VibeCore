package com.siberanka.vibecore.core.validation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Typed, problem-collecting reader over a plain map tree such as a parsed YAML
 * or JSON document. Every read records a {@link Problem} instead of throwing,
 * so one pass reports every defect of a candidate.
 */
public final class MapReader {

    private final Map<String, Object> values;
    private final String prefix;
    private final List<Problem> problems;

    private MapReader(Map<String, Object> values, String prefix, List<Problem> problems) {
        this.values = values;
        this.prefix = prefix;
        this.problems = problems;
    }

    public static MapReader root(Map<String, ?> values) {
        return new MapReader(copy(values), "", new ArrayList<>());
    }

    private static Map<String, Object> copy(Map<?, ?> source) {
        Map<String, Object> copy = new LinkedHashMap<>();
        if (source != null) {
            source.forEach((key, value) -> copy.put(String.valueOf(key), value));
        }
        return copy;
    }

    public List<Problem> problems() {
        return List.copyOf(problems);
    }

    public boolean hasProblems() {
        return !problems.isEmpty();
    }

    public void report(String key, String code, String detail) {
        problems.add(new Problem(path(key), code, detail));
    }

    public String path(String key) {
        return prefix.isEmpty() ? key : prefix + "." + key;
    }

    /** Reports keys that the schema does not know, which usually indicates a typo. */
    public void rejectUnknownKeys(Set<String> known) {
        for (String key : values.keySet()) {
            if (!known.contains(key)) {
                report(key, "unknown-key", "unknown setting");
            }
        }
    }

    public MapReader section(String key) {
        Object raw = values.get(key);
        if (raw == null) {
            return new MapReader(new LinkedHashMap<>(), path(key), problems);
        }
        if (raw instanceof Map<?, ?> map) {
            return new MapReader(copy(map), path(key), problems);
        }
        report(key, "type", "expected a section");
        return new MapReader(new LinkedHashMap<>(), path(key), problems);
    }

    public Set<String> keys() {
        return values.keySet();
    }

    public boolean has(String key) {
        return values.containsKey(key);
    }

    public int integer(String key, int fallback, int min, int max) {
        Object raw = values.get(key);
        if (raw == null) {
            return fallback;
        }
        if (!(raw instanceof Integer || raw instanceof Long || raw instanceof Short || raw instanceof Byte)) {
            report(key, "type", "expected a whole number");
            return fallback;
        }
        long value = ((Number) raw).longValue();
        if (value < min || value > max) {
            report(key, "range", "must be between " + min + " and " + max);
            return fallback;
        }
        return (int) value;
    }

    /** Reads a finite number (whole or decimal) within the given bounds. */
    public double number(String key, double fallback, double min, double max) {
        Object raw = values.get(key);
        if (raw == null) {
            return fallback;
        }
        if (!(raw instanceof Number number) || !Double.isFinite(number.doubleValue())) {
            report(key, "type", "expected a number");
            return fallback;
        }
        double value = number.doubleValue();
        if (value < min || value > max) {
            report(key, "range", "must be between " + min + " and " + max);
            return fallback;
        }
        return value;
    }

    public boolean bool(String key, boolean fallback) {
        Object raw = values.get(key);
        if (raw == null) {
            return fallback;
        }
        if (raw instanceof Boolean value) {
            return value;
        }
        report(key, "type", "expected true or false");
        return fallback;
    }

    public Optional<String> text(String key, int maxLength) {
        Object raw = values.get(key);
        if (raw == null) {
            return Optional.empty();
        }
        if (!(raw instanceof String value)) {
            report(key, "type", "expected text");
            return Optional.empty();
        }
        if (value.length() > maxLength) {
            report(key, "length", "must not exceed " + maxLength + " characters");
            return Optional.empty();
        }
        return Optional.of(value);
    }

    public String text(String key, String fallback, int maxLength) {
        return text(key, maxLength).orElse(fallback);
    }
}
