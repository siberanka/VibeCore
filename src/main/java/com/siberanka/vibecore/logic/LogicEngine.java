package com.siberanka.vibecore.logic;

import com.siberanka.vibecore.core.validation.Problem;
import com.siberanka.vibecore.core.validation.Result;
import com.siberanka.vibecore.project.Identifier;
import com.siberanka.vibecore.variable.VariableContext;
import com.siberanka.vibecore.variable.VariableDefinition;
import com.siberanka.vibecore.variable.VariableStore;
import com.siberanka.vibecore.variable.VariableValue;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.LongSupplier;
import java.util.random.RandomGenerator;

/**
 * Runs action chains fail-fast against a staged view of the variable store.
 *
 * <p>Reads see the chain's own staged writes. Nothing reaches the store until
 * the chain finishes successfully, and the final writes are applied with
 * {@link VariableStore#applyAll}, so a chain changes either everything or
 * nothing. Every chain is bounded by a step and nesting budget.
 */
public final class LogicEngine {

    /** Hard bounds for one chain run. */
    public record Limits(int maxSteps, int maxDepth) {
        public static final Limits DEFAULT = new Limits(256, 16);

        public Limits {
            if (maxSteps < 1 || maxDepth < 1) {
                throw new IllegalArgumentException("limits must be positive");
            }
        }
    }

    private final VariableStore store;
    private final RandomGenerator random;
    private final LongSupplier clock;

    public LogicEngine(VariableStore store, RandomGenerator random) {
        this(store, random, () -> System.currentTimeMillis() / 1000);
    }

    /**
     * @param clock current time in epoch seconds, used by cooldowns; wall-clock time
     *              so that stored cooldowns stay meaningful across restarts
     */
    public LogicEngine(VariableStore store, RandomGenerator random, LongSupplier clock) {
        this.store = Objects.requireNonNull(store, "store");
        this.random = Objects.requireNonNull(random, "random");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Formats seconds as a compact duration such as {@code 1h 05m 09s}. */
    static String duration(long seconds) {
        long days = seconds / 86400;
        long hours = seconds % 86400 / 3600;
        long minutes = seconds % 3600 / 60;
        long rest = seconds % 60;
        if (days > 0) {
            return days + "d " + String.format("%02dh %02dm", hours, minutes);
        }
        if (hours > 0) {
            return hours + "h " + String.format("%02dm %02ds", minutes, rest);
        }
        return minutes > 0 ? minutes + "m " + String.format("%02ds", rest) : rest + "s";
    }

    public Outcome run(List<Action> chain, VariableContext context, Limits limits) {
        return run(chain, context, limits, Facts.NONE, SideEffects.NONE);
    }

    /**
     * Runs a chain whose conditions may consult {@code facts} and whose external
     * effects are performed by {@code sideEffects} together with the variable
     * changes: if either side fails, neither takes effect.
     */
    public Outcome run(List<Action> chain, VariableContext context, Limits limits, Facts facts,
                       SideEffects sideEffects) {
        Run run = new Run(context, limits, facts);
        try {
            run.actions(chain, 1);
        } catch (Stop stop) {
            return stop.outcome(run.steps);
        }
        List<VariableStore.Write> writes = new ArrayList<>();
        run.staged.forEach((key, value) -> writes.add(new VariableStore.Write(key, context, value.orElse(null))));
        Result<SideEffects.Undo> performed = sideEffects.perform(run.effects);
        if (!performed.isAccepted()) {
            Problem first = performed.problems().getFirst();
            return first.code().equals(SideEffects.UNMET)
                    ? new Outcome.Failed(first.detail(), run.steps)
                    : new Outcome.Rejected(performed.problems(), run.steps);
        }
        Result<Integer> applied = store.applyAll(writes);
        if (!applied.isAccepted()) {
            performed.orThrow().undo();
            return new Outcome.Rejected(applied.problems(), run.steps);
        }
        return new Outcome.Committed(run.effects, writes.size(), run.steps);
    }

    /** Evaluates a condition without changing anything, for visibility and open checks. */
    public Result<Boolean> test(Condition condition, VariableContext context, Limits limits) {
        return test(condition, context, limits, Facts.NONE);
    }

    public Result<Boolean> test(Condition condition, VariableContext context, Limits limits, Facts facts) {
        Run run = new Run(context, limits, facts);
        try {
            return Result.accepted(run.condition(condition, 1));
        } catch (Stop stop) {
            return Result.rejected(stop.problems.isEmpty()
                    ? List.of(new Problem("condition", "failed", stop.reason)) : stop.problems);
        }
    }

    private static final class Stop extends RuntimeException {
        private final String reason;
        private final List<Problem> problems;

        Stop(String reason) {
            super(reason, null, false, false);
            this.reason = reason;
            this.problems = List.of();
        }

        Stop(List<Problem> problems) {
            super("rejected", null, false, false);
            this.reason = null;
            this.problems = List.copyOf(problems);
        }

        Outcome outcome(int steps) {
            return problems.isEmpty() ? new Outcome.Failed(reason, steps) : new Outcome.Rejected(problems, steps);
        }
    }

    /** Optional-like holder that can represent a staged reset. */
    private record Staged(VariableValue value) {
        VariableValue orElse(VariableValue fallback) {
            return value != null ? value : fallback;
        }
    }

    private final class Run {
        private final VariableContext context;
        private final Limits limits;
        private final Facts facts;
        private final Map<Identifier, Staged> staged = new LinkedHashMap<>();
        private final List<Action.Emit> effects = new ArrayList<>();
        private int steps;

        Run(VariableContext context, Limits limits, Facts facts) {
            this.context = context;
            this.limits = limits;
            this.facts = facts;
        }

        private void step(int depth) {
            if (++steps > limits.maxSteps()) {
                throw new Stop(List.of(new Problem("chain", "step-limit",
                        "exceeded " + limits.maxSteps() + " steps")));
            }
            if (depth > limits.maxDepth()) {
                throw new Stop(List.of(new Problem("chain", "depth-limit",
                        "nested deeper than " + limits.maxDepth())));
            }
        }

        private <T> T require(Result<T> result) {
            if (!result.isAccepted()) {
                throw new Stop(result.problems());
            }
            return result.orThrow();
        }

        private VariableValue read(Identifier key) {
            VariableDefinition definition = require(store.definition(key));
            Staged pending = staged.get(key);
            if (pending != null) {
                return pending.orElse(definition.defaultValue());
            }
            return require(store.get(key, context));
        }

        private void write(Identifier key, VariableValue value) {
            VariableDefinition definition = require(store.definition(key));
            require(definition.check(value));
            staged.put(key, new Staged(value));
        }

        private VariableValue resolve(Operand operand) {
            return switch (operand) {
                case Operand.Literal literal -> literal.value();
                case Operand.Variable variable -> read(variable.key());
                case Operand.Data data -> require(facts.data(data.name()));
            };
        }

        void actions(List<Action> chain, int depth) {
            for (Action action : chain) {
                action(action, depth);
            }
        }

        private void action(Action action, int depth) {
            step(depth);
            switch (action) {
                case Action.SetVariable set -> write(set.key(), resolve(set.value()));
                case Action.AddVariable add -> {
                    if (!(read(add.key()) instanceof VariableValue.NumberValue number)) {
                        throw new Stop(List.of(new Problem(add.key().toString(), "type",
                                "only number variables can be added to")));
                    }
                    double next = number.value() + add.amount();
                    if (!Double.isFinite(next)) {
                        throw new Stop(List.of(new Problem(add.key().toString(), "range", "result is not finite")));
                    }
                    write(add.key(), VariableValue.number(next));
                }
                case Action.ToggleVariable toggle -> {
                    if (!(read(toggle.key()) instanceof VariableValue.BooleanValue flag)) {
                        throw new Stop(List.of(new Problem(toggle.key().toString(), "type",
                                "only boolean variables can be toggled")));
                    }
                    write(toggle.key(), VariableValue.bool(!flag.value()));
                }
                case Action.ResetVariable reset -> {
                    require(store.definition(reset.key()));
                    staged.put(reset.key(), new Staged(null));
                }
                case Action.If branch -> actions(condition(branch.condition(), depth + 1)
                        ? branch.then() : branch.otherwise(), depth + 1);
                case Action.Cooldown cooldown -> {
                    if (!(read(cooldown.key()) instanceof VariableValue.NumberValue last)) {
                        throw new Stop(List.of(new Problem(cooldown.key().toString(), "type",
                                "cooldowns need a number variable")));
                    }
                    long now = clock.getAsLong();
                    long elapsed = now - (long) last.value();
                    // A stored time in the future means the clock moved back; it still blocks for at most one period.
                    long remaining = elapsed < 0 ? Math.min(cooldown.seconds(), -elapsed) : cooldown.seconds() - elapsed;
                    if (last.value() > 0 && remaining > 0 && (elapsed >= 0 || -elapsed <= cooldown.seconds())) {
                        throw new Stop(cooldown.fail().replace("<remaining>", duration(remaining)));
                    }
                    write(cooldown.key(), VariableValue.number(now));
                }
                case Action.Fail fail -> throw new Stop(fail.reason());
                case Action.Emit emit -> effects.add(emit);
            }
        }

        boolean condition(Condition condition, int depth) {
            step(depth);
            return switch (condition) {
                case Condition.Compare compare -> compare(compare);
                case Condition.All all -> {
                    for (Condition child : all.conditions()) {
                        if (!condition(child, depth + 1)) {
                            yield false;
                        }
                    }
                    yield true;
                }
                case Condition.Any any -> {
                    for (Condition child : any.conditions()) {
                        if (condition(child, depth + 1)) {
                            yield true;
                        }
                    }
                    yield false;
                }
                case Condition.Not not -> !condition(not.condition(), depth + 1);
                case Condition.Chance chance -> random.nextDouble() < chance.probability();
                case Condition.Fact fact -> require(facts.test(fact));
            };
        }

        private boolean compare(Condition.Compare compare) {
            VariableValue left = resolve(compare.left());
            VariableValue right = resolve(compare.right());
            if (left.type() != right.type()) {
                throw new Stop(List.of(new Problem("compare", "type",
                        "cannot compare " + left.type().key() + " with " + right.type().key())));
            }
            Condition.Comparison comparison = compare.comparison();
            if (comparison.ordering()) {
                if (!(left instanceof VariableValue.NumberValue a) || !(right instanceof VariableValue.NumberValue b)) {
                    throw new Stop(List.of(new Problem("compare", "type",
                            comparison.symbol() + " needs numbers")));
                }
                int order = Double.compare(a.value(), b.value());
                return switch (comparison) {
                    case LESS -> order < 0;
                    case LESS_OR_EQUAL -> order <= 0;
                    case GREATER -> order > 0;
                    case GREATER_OR_EQUAL -> order >= 0;
                    default -> throw new IllegalStateException();
                };
            }
            boolean equal = left.equals(right);
            return comparison == Condition.Comparison.EQUAL ? equal : !equal;
        }
    }
}
