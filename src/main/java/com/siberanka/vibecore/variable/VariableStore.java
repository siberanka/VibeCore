package com.siberanka.vibecore.variable;

import com.siberanka.vibecore.core.validation.Problem;
import com.siberanka.vibecore.core.validation.Result;
import com.siberanka.vibecore.project.Identifier;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Typed variable values for all four scopes.
 *
 * <p>Session and online-player values live in memory and are dropped by
 * {@link #closeSession(UUID)} and {@link #disconnect(UUID)}. Persistent values
 * are written through {@link #serializePersistent()}. Persisted entries that
 * no longer match a definition are kept verbatim as orphans and written back,
 * so removing or retyping a definition never silently deletes stored data.
 *
 * <p>Writes are validated against the definition and never clamped: a value
 * outside its bounds is rejected with a reason.
 */
public final class VariableStore {

    private static final String HEADER = "vibecore-variables 1";

    /** Address of one stored value. Fields not used by the scope are null. */
    private record Slot(Identifier key, UUID subject, UUID session) {
    }

    private final Map<Identifier, VariableDefinition> definitions;
    private final int maxValuesPerPlayer;
    private final Map<Slot, VariableValue> values = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> perPlayerCount = new ConcurrentHashMap<>();
    private final List<String> orphans = new ArrayList<>();
    private volatile boolean persistentDirty;

    private VariableStore(Map<Identifier, VariableDefinition> definitions, int maxValuesPerPlayer) {
        this.definitions = definitions;
        this.maxValuesPerPlayer = maxValuesPerPlayer;
    }

    public static Result<VariableStore> create(Collection<VariableDefinition> definitions, int maxValuesPerPlayer) {
        if (maxValuesPerPlayer < 1) {
            throw new IllegalArgumentException("maxValuesPerPlayer must be positive");
        }
        Map<Identifier, VariableDefinition> byKey = new HashMap<>();
        List<Problem> problems = new ArrayList<>();
        for (VariableDefinition definition : definitions) {
            if (byKey.putIfAbsent(definition.key(), definition) != null) {
                problems.add(new Problem(definition.key().toString(), "duplicate", "variable declared twice"));
            }
        }
        if (!problems.isEmpty()) {
            return Result.rejected(problems);
        }
        return Result.accepted(new VariableStore(Map.copyOf(byKey), maxValuesPerPlayer));
    }

    public Result<VariableDefinition> definition(Identifier key) {
        VariableDefinition definition = definitions.get(key);
        return definition == null
                ? Result.rejected(key.toString(), "unknown-variable", "no such variable")
                : Result.accepted(definition);
    }

    private static Slot slot(VariableDefinition definition, VariableContext context) {
        VariableScope scope = definition.scope();
        return new Slot(definition.key(),
                scope.playerBound() ? context.subject() : null,
                scope == VariableScope.SESSION ? context.session() : null);
    }

    public Result<VariableValue> get(Identifier key, VariableContext context) {
        Result<VariableDefinition> found = definition(key);
        if (!found.isAccepted()) {
            return Result.rejected(found.problems());
        }
        VariableDefinition definition = found.orThrow();
        Result<VariableContext> valid = context.require(definition.scope(), key.toString());
        if (!valid.isAccepted()) {
            return Result.rejected(valid.problems());
        }
        return Result.accepted(values.getOrDefault(slot(definition, context), definition.defaultValue()));
    }

    public Result<VariableValue> set(Identifier key, VariableContext context, VariableValue value) {
        Result<VariableDefinition> found = definition(key);
        if (!found.isAccepted()) {
            return Result.rejected(found.problems());
        }
        VariableDefinition definition = found.orThrow();
        Result<VariableContext> valid = context.require(definition.scope(), key.toString());
        if (!valid.isAccepted()) {
            return Result.rejected(valid.problems());
        }
        Result<VariableValue> checked = definition.check(value);
        if (!checked.isAccepted()) {
            return checked;
        }
        Slot slot = slot(definition, context);
        synchronized (this) {
            boolean isNew = !values.containsKey(slot);
            if (isNew && slot.subject() != null
                    && perPlayerCount.getOrDefault(slot.subject(), 0) >= maxValuesPerPlayer) {
                return Result.rejected(key.toString(), "limit",
                        "player already has " + maxValuesPerPlayer + " stored values");
            }
            values.put(slot, value);
            if (isNew && slot.subject() != null) {
                perPlayerCount.merge(slot.subject(), 1, Integer::sum);
            }
        }
        if (definition.scope().persistent()) {
            persistentDirty = true;
        }
        return Result.accepted(value);
    }

    /** Adds {@code delta} to a number variable; the result must stay within bounds. */
    public Result<VariableValue> add(Identifier key, VariableContext context, double delta) {
        Result<VariableValue> current = get(key, context);
        if (!current.isAccepted()) {
            return current;
        }
        if (!(current.orThrow() instanceof VariableValue.NumberValue number)) {
            return Result.rejected(key.toString(), "type", "only number variables can be added to");
        }
        double next = number.value() + delta;
        if (!Double.isFinite(delta) || !Double.isFinite(next)) {
            return Result.rejected(key.toString(), "range", "result is not a finite number");
        }
        return set(key, context, VariableValue.number(next));
    }

    public Result<VariableValue> toggle(Identifier key, VariableContext context) {
        Result<VariableValue> current = get(key, context);
        if (!current.isAccepted()) {
            return current;
        }
        if (!(current.orThrow() instanceof VariableValue.BooleanValue flag)) {
            return Result.rejected(key.toString(), "type", "only boolean variables can be toggled");
        }
        return set(key, context, VariableValue.bool(!flag.value()));
    }

    /** Returns the variable to its default by removing the stored value. */
    public Result<VariableValue> reset(Identifier key, VariableContext context) {
        Result<VariableValue> current = get(key, context);
        if (!current.isAccepted()) {
            return current;
        }
        VariableDefinition definition = definitions.get(key);
        remove(slot(definition, context));
        if (definition.scope().persistent()) {
            persistentDirty = true;
        }
        return Result.accepted(definition.defaultValue());
    }

    /** One staged change; a null value resets the variable to its default. */
    public record Write(Identifier key, VariableContext context, VariableValue value) {
        public Write {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(context, "context");
        }
    }

    /**
     * Applies every write or none of them. All writes are validated, including
     * the per-player limit after the whole batch, before anything changes.
     */
    public synchronized Result<Integer> applyAll(List<Write> writes) {
        List<Problem> problems = new ArrayList<>();
        Map<Slot, VariableValue> staged = new java.util.LinkedHashMap<>();
        for (Write write : writes) {
            VariableDefinition definition = definitions.get(write.key());
            if (definition == null) {
                problems.add(new Problem(write.key().toString(), "unknown-variable", "no such variable"));
                continue;
            }
            Result<VariableContext> valid = write.context().require(definition.scope(), write.key().toString());
            problems.addAll(valid.problems());
            if (write.value() != null) {
                problems.addAll(definition.check(write.value()).problems());
            }
            if (valid.isAccepted()) {
                staged.put(slot(definition, write.context()), write.value());
            }
        }
        Map<UUID, Integer> counts = new HashMap<>(perPlayerCount);
        staged.forEach((slot, value) -> {
            if (slot.subject() == null) {
                return;
            }
            boolean present = values.containsKey(slot);
            if (value != null && !present) {
                counts.merge(slot.subject(), 1, Integer::sum);
            } else if (value == null && present) {
                counts.merge(slot.subject(), -1, Integer::sum);
            }
        });
        counts.forEach((subject, count) -> {
            if (count > maxValuesPerPlayer && count > perPlayerCount.getOrDefault(subject, 0)) {
                problems.add(new Problem(subject.toString(), "limit",
                        "player would exceed " + maxValuesPerPlayer + " stored values"));
            }
        });
        if (!problems.isEmpty()) {
            return Result.rejected(problems);
        }
        staged.forEach((slot, value) -> {
            if (value == null) {
                values.remove(slot);
            } else {
                values.put(slot, value);
            }
            if (definitions.get(slot.key()).scope().persistent()) {
                persistentDirty = true;
            }
        });
        counts.forEach((subject, count) -> {
            if (count > 0) {
                perPlayerCount.put(subject, count);
            } else {
                perPlayerCount.remove(subject);
            }
        });
        return Result.accepted(staged.size());
    }

    private synchronized void remove(Slot slot) {
        if (values.remove(slot) != null && slot.subject() != null) {
            perPlayerCount.computeIfPresent(slot.subject(), (subject, count) -> count > 1 ? count - 1 : null);
        }
    }

    /** Drops every value of one screen session. */
    public void closeSession(UUID session) {
        Objects.requireNonNull(session, "session");
        for (Slot slot : List.copyOf(values.keySet())) {
            if (session.equals(slot.session())) {
                remove(slot);
            }
        }
    }

    /** Drops the in-memory values of a player who left; persistent values stay. */
    public void disconnect(UUID subject) {
        Objects.requireNonNull(subject, "subject");
        for (Slot slot : List.copyOf(values.keySet())) {
            if (subject.equals(slot.subject()) && !definitions.get(slot.key()).scope().persistent()) {
                remove(slot);
            }
        }
    }

    public boolean persistentDirty() {
        return persistentDirty;
    }

    /** Flags persistent values for saving again, for example after a failed write. */
    public void markPersistentDirty() {
        persistentDirty = true;
    }

    public int orphanCount() {
        synchronized (orphans) {
            return orphans.size();
        }
    }

    /** Deterministic text form of persistent values and preserved orphans; clears the dirty flag. */
    public String serializePersistent() {
        TreeMap<String, String> lines = new TreeMap<>();
        values.forEach((slot, value) -> {
            VariableDefinition definition = definitions.get(slot.key());
            if (definition.scope() == VariableScope.PERSISTENT_PLAYER) {
                String line = "player " + slot.subject() + " " + slot.key() + " " + encode(value);
                lines.put(line, line);
            } else if (definition.scope() == VariableScope.PERSISTENT_GLOBAL) {
                String line = "global - " + slot.key() + " " + encode(value);
                lines.put(line, line);
            }
        });
        synchronized (orphans) {
            orphans.forEach(line -> lines.put(line, line));
        }
        persistentDirty = false;
        StringBuilder out = new StringBuilder(HEADER).append('\n');
        lines.values().forEach(line -> out.append(line).append('\n'));
        return out.toString();
    }

    /**
     * Loads persistent values. Lines that no longer match a definition are
     * preserved as orphans; malformed lines reject the whole file.
     */
    public Result<Integer> loadPersistent(String text) {
        String[] lines = text.split("\n", -1);
        if (lines.length == 0 || !lines[0].trim().equals(HEADER)) {
            return Result.rejected("header", "format", "not a VibeCore variable file of a supported version");
        }
        List<Problem> problems = new ArrayList<>();
        Map<Slot, VariableValue> loaded = new HashMap<>();
        List<String> loadedOrphans = new ArrayList<>();
        for (int index = 1; index < lines.length; index++) {
            String line = lines[index].trim();
            if (line.isEmpty()) {
                continue;
            }
            String location = "line " + (index + 1);
            String[] parts = line.split(" ");
            if (parts.length != 5 || !(parts[0].equals("player") || parts[0].equals("global"))) {
                problems.add(new Problem(location, "format", "malformed entry"));
                continue;
            }
            UUID subject;
            Identifier key;
            VariableValue value;
            try {
                subject = parts[0].equals("player") ? UUID.fromString(parts[1]) : null;
                if (subject == null && !parts[1].equals("-")) {
                    throw new IllegalArgumentException("global entries have no subject");
                }
                key = Identifier.parse("key", parts[2]).orThrow();
                value = decode(parts[3], parts[4]);
            } catch (RuntimeException malformed) {
                problems.add(new Problem(location, "format", "malformed entry"));
                continue;
            }
            VariableDefinition definition = definitions.get(key);
            VariableScope expected = subject == null ? VariableScope.PERSISTENT_GLOBAL : VariableScope.PERSISTENT_PLAYER;
            if (definition == null || definition.scope() != expected || !definition.check(value).isAccepted()) {
                loadedOrphans.add(line);
                continue;
            }
            loaded.put(new Slot(key, subject, null), value);
        }
        if (!problems.isEmpty()) {
            return Result.rejected(problems);
        }
        synchronized (this) {
            loaded.forEach((slot, value) -> {
                if (values.put(slot, value) == null && slot.subject() != null) {
                    perPlayerCount.merge(slot.subject(), 1, Integer::sum);
                }
            });
        }
        synchronized (orphans) {
            orphans.clear();
            orphans.addAll(loadedOrphans);
        }
        return Result.accepted(loaded.size());
    }

    private static String encode(VariableValue value) {
        String payload = value instanceof VariableValue.TextValue text
                ? Base64.getEncoder().withoutPadding().encodeToString(text.value().getBytes(StandardCharsets.UTF_8))
                : value.canonical();
        return value.type().key() + " " + (payload.isEmpty() ? "=" : payload);
    }

    private static VariableValue decode(String type, String payload) {
        return switch (type) {
            case "number" -> VariableValue.number(Double.parseDouble(payload));
            case "boolean" -> switch (payload) {
                case "true" -> VariableValue.bool(true);
                case "false" -> VariableValue.bool(false);
                default -> throw new IllegalArgumentException("invalid boolean");
            };
            case "text" -> VariableValue.text(payload.equals("=") ? ""
                    : new String(Base64.getDecoder().decode(payload), StandardCharsets.UTF_8));
            default -> throw new IllegalArgumentException("unknown type");
        };
    }
}
