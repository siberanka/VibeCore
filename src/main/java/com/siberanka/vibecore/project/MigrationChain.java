package com.siberanka.vibecore.project;

import com.siberanka.vibecore.core.validation.Result;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Explicit, gap-free chain of single-step schema migrations. Documents from a
 * newer schema are rejected rather than guessed at; the input document is
 * never modified.
 */
public final class MigrationChain {

    public static final String VERSION_KEY = "schema-version";

    private final int currentVersion;
    private final Map<Integer, SchemaMigration> steps;

    private MigrationChain(int currentVersion, Map<Integer, SchemaMigration> steps) {
        this.currentVersion = currentVersion;
        this.steps = steps;
    }

    public static MigrationChain of(int currentVersion, List<SchemaMigration> migrations) {
        if (currentVersion < 1) {
            throw new IllegalArgumentException("currentVersion must be at least 1");
        }
        Map<Integer, SchemaMigration> steps = new TreeMap<>();
        for (SchemaMigration migration : migrations) {
            int from = migration.fromVersion();
            if (from < 1 || from >= currentVersion) {
                throw new IllegalArgumentException("Migration outside 1.." + (currentVersion - 1) + ": " + from);
            }
            if (steps.putIfAbsent(from, migration) != null) {
                throw new IllegalArgumentException("Duplicate migration from version " + from);
            }
        }
        for (int version = 1; version < currentVersion; version++) {
            if (!steps.containsKey(version)) {
                throw new IllegalArgumentException("Missing migration from version " + version);
            }
        }
        return new MigrationChain(currentVersion, Map.copyOf(steps));
    }

    public int currentVersion() {
        return currentVersion;
    }

    public Result<Map<String, Object>> migrate(Map<String, ?> document) {
        Object raw = document.get(VERSION_KEY);
        if (!(raw instanceof Integer || raw instanceof Long)) {
            return Result.rejected(VERSION_KEY, "missing", "a whole-number schema version is required");
        }
        long declared = ((Number) raw).longValue();
        if (declared < 1) {
            return Result.rejected(VERSION_KEY, "range", "must be at least 1");
        }
        if (declared > currentVersion) {
            return Result.rejected(VERSION_KEY, "future-version",
                    "document uses schema " + declared + " but this build supports up to " + currentVersion);
        }
        Map<String, Object> working = deepCopy(document);
        for (int version = (int) declared; version < currentVersion; version++) {
            try {
                working = deepCopy(steps.get(version).apply(working));
            } catch (SchemaMigration.MigrationException | RuntimeException failure) {
                return Result.rejected(VERSION_KEY, "migration-failed",
                        "upgrade from schema " + version + " failed: " + failure.getMessage());
            }
            working.put(VERSION_KEY, version + 1);
        }
        return Result.accepted(working);
    }

    static Map<String, Object> deepCopy(Map<?, ?> source) {
        Map<String, Object> copy = new LinkedHashMap<>();
        source.forEach((key, value) -> copy.put(String.valueOf(key), copyValue(value)));
        return copy;
    }

    private static Object copyValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            return deepCopy(map);
        }
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>(list.size());
            for (Object element : list) {
                copy.add(copyValue(element));
            }
            return copy;
        }
        return value;
    }
}
