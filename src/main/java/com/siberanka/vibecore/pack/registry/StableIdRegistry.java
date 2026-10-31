package com.siberanka.vibecore.pack.registry;

import com.siberanka.vibecore.core.validation.Problem;
import com.siberanka.vibecore.core.validation.Result;
import com.siberanka.vibecore.project.Identifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Persistent allocation of numeric identities (glyph code points, model data
 * values) to stable asset identifiers.
 *
 * <p>Allocations never move. Releasing an identifier leaves a tombstone, so the
 * value is not handed to a different asset while old chat prefixes, item
 * stacks or exported packs may still refer to it. Re-allocating the same
 * identifier restores its tombstoned value. A tombstoned value becomes free
 * again only through an explicit {@link #reclaim(int)} migration step. Values
 * used by foreign packs are excluded with {@link #reserve(int)}.
 */
public final class StableIdRegistry {

    private static final String HEADER = "vibecore-stable-ids";
    private static final int FORMAT = 1;

    private final String name;
    private final int min;
    private final int max;
    private final Map<Identifier, Integer> active = new HashMap<>();
    private final TreeMap<Integer, Identifier> byValue = new TreeMap<>();
    private final TreeMap<Integer, Identifier> tombstones = new TreeMap<>();
    private final TreeSet<Integer> reserved = new TreeSet<>();
    private long revision;

    public StableIdRegistry(String name, int min, int max) {
        if (name == null || !name.matches("[a-z0-9_.:/-]{1,64}")) {
            throw new IllegalArgumentException("Invalid registry name");
        }
        if (min < 0 || min > max) {
            throw new IllegalArgumentException("Invalid range " + min + ".." + max);
        }
        this.name = name;
        this.min = min;
        this.max = max;
    }

    /**
     * Glyph registry restricted to the Basic Multilingual Plane private use
     * area. Bedrock bitmap glyph pages only address the BMP, so badge glyphs
     * must stay inside U+E000..U+F8FF to keep one code point for both editions.
     */
    public static StableIdRegistry glyphs(String name, int firstCodePoint, int lastCodePoint) {
        if (firstCodePoint < 0xE000 || lastCodePoint > 0xF8FF) {
            throw new IllegalArgumentException("Glyph range must be inside U+E000..U+F8FF");
        }
        return new StableIdRegistry(name, firstCodePoint, lastCodePoint);
    }

    public String name() {
        return name;
    }

    public long revision() {
        return revision;
    }

    public Optional<Integer> lookup(Identifier id) {
        return Optional.ofNullable(active.get(id));
    }

    public Optional<Identifier> owner(int value) {
        return Optional.ofNullable(byValue.get(value));
    }

    /** Returns the existing value of {@code id}, its tombstoned value, or the lowest free value. */
    public Result<Integer> allocate(Identifier id) {
        Integer existing = active.get(id);
        if (existing != null) {
            return Result.accepted(existing);
        }
        for (Map.Entry<Integer, Identifier> tombstone : tombstones.entrySet()) {
            if (tombstone.getValue().equals(id)) {
                int value = tombstone.getKey();
                tombstones.remove(value);
                bind(id, value);
                return Result.accepted(value);
            }
        }
        for (int value = min; value <= max; value++) {
            if (isFree(value)) {
                bind(id, value);
                return Result.accepted(value);
            }
        }
        return Result.rejected(name, "exhausted", "no free value left in " + min + ".." + max);
    }

    /** Binds an explicit value, for imports that must keep their numbers. */
    public Result<Integer> claim(Identifier id, int value) {
        if (value < min || value > max) {
            return Result.rejected(name, "range", value + " is outside " + min + ".." + max);
        }
        Integer existing = active.get(id);
        if (existing != null) {
            return existing == value ? Result.accepted(value)
                    : Result.rejected(name, "conflict", id + " already owns " + existing);
        }
        Identifier tombstoneOwner = tombstones.get(value);
        if (tombstoneOwner != null && !tombstoneOwner.equals(id)) {
            return Result.rejected(name, "tombstoned", value + " was released by " + tombstoneOwner
                    + " and needs an explicit reclaim");
        }
        if (byValue.containsKey(value) || reserved.contains(value)) {
            return Result.rejected(name, "conflict", value + " is already in use");
        }
        tombstones.remove(value);
        bind(id, value);
        return Result.accepted(value);
    }

    private boolean isFree(int value) {
        return !byValue.containsKey(value) && !tombstones.containsKey(value) && !reserved.contains(value);
    }

    private void bind(Identifier id, int value) {
        active.put(id, value);
        byValue.put(value, id);
        revision++;
    }

    /** Releases {@code id}; its value stays tombstoned for that identifier. */
    public boolean release(Identifier id) {
        Integer value = active.remove(id);
        if (value == null) {
            return false;
        }
        byValue.remove(value);
        tombstones.put(value, id);
        revision++;
        return true;
    }

    /** Explicit migration step: makes a tombstoned value allocatable again. */
    public boolean reclaim(int value) {
        if (tombstones.remove(value) == null) {
            return false;
        }
        revision++;
        return true;
    }

    /** Excludes a value used by content outside this registry, such as another pack. */
    public Result<Integer> reserve(int value) {
        if (value < min || value > max) {
            return Result.rejected(name, "range", value + " is outside " + min + ".." + max);
        }
        Identifier owner = byValue.get(value);
        if (owner != null) {
            return Result.rejected(name, "conflict", value + " is already allocated to " + owner);
        }
        if (reserved.add(value)) {
            revision++;
        }
        return Result.accepted(value);
    }

    public int activeCount() {
        return active.size();
    }

    public int tombstoneCount() {
        return tombstones.size();
    }

    public int reservedCount() {
        return reserved.size();
    }

    /** Deterministic text form, sorted by value. */
    public String serialize() {
        StringBuilder out = new StringBuilder();
        out.append(HEADER).append(' ').append(FORMAT).append(' ').append(name).append(' ')
                .append(min).append(' ').append(max).append('\n');
        TreeMap<Integer, String> lines = new TreeMap<>();
        byValue.forEach((value, id) -> lines.put(value, "active " + value + " " + id));
        tombstones.forEach((value, id) -> lines.put(value, "tombstone " + value + " " + id));
        reserved.forEach(value -> lines.put(value, "reserved " + value));
        lines.values().forEach(line -> out.append(line).append('\n'));
        return out.toString();
    }

    public static Result<StableIdRegistry> deserialize(String text) {
        String[] lines = text.split("\n", -1);
        String[] header = lines.length == 0 ? new String[0] : lines[0].trim().split(" ");
        if (header.length != 5 || !header[0].equals(HEADER)) {
            return Result.rejected("header", "format", "not a VibeCore stable id registry");
        }
        StableIdRegistry registry;
        try {
            if (Integer.parseInt(header[1]) != FORMAT) {
                return Result.rejected("header", "future-version", "unsupported registry format " + header[1]);
            }
            registry = new StableIdRegistry(header[2], Integer.parseInt(header[3]), Integer.parseInt(header[4]));
        } catch (IllegalArgumentException invalid) {
            return Result.rejected("header", "format", "invalid registry header");
        }
        List<Problem> problems = new ArrayList<>();
        for (int index = 1; index < lines.length; index++) {
            String line = lines[index].trim();
            if (line.isEmpty()) {
                continue;
            }
            String location = "line " + (index + 1);
            String[] parts = line.split(" ");
            try {
                int value = Integer.parseInt(parts[1]);
                Result<?> outcome = switch (parts[0]) {
                    case "active" -> parts.length == 3
                            ? registry.claim(parseId(parts[2]), value)
                            : Result.rejected(location, "format", "expected: active <value> <id>");
                    case "tombstone" -> parts.length == 3
                            ? registry.restoreTombstone(parseId(parts[2]), value)
                            : Result.rejected(location, "format", "expected: tombstone <value> <id>");
                    case "reserved" -> parts.length == 2
                            ? registry.reserve(value)
                            : Result.rejected(location, "format", "expected: reserved <value>");
                    default -> Result.rejected(location, "format", "unknown entry kind");
                };
                for (Problem problem : outcome.problems()) {
                    problems.add(new Problem(location, problem.code(), problem.detail()));
                }
            } catch (RuntimeException invalid) {
                problems.add(new Problem(location, "format", "malformed entry"));
            }
        }
        if (!problems.isEmpty()) {
            return Result.rejected(problems);
        }
        registry.revision = 0;
        return Result.accepted(registry);
    }

    private static Identifier parseId(String text) {
        return Identifier.parse("id", text).orThrow();
    }

    private Result<Integer> restoreTombstone(Identifier id, int value) {
        if (value < min || value > max || !isFree(value)) {
            return Result.rejected(name, "conflict", value + " is out of range or already used");
        }
        tombstones.put(value, id);
        return Result.accepted(value);
    }
}
