package com.siberanka.vibecore.logic;

import com.siberanka.vibecore.project.Identifier;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * One step of an action chain. Variable changes are staged and committed
 * together only when the whole chain succeeds; effects such as messages are
 * returned to the caller, which performs them after the commit.
 */
public sealed interface Action permits Action.SetVariable, Action.AddVariable, Action.ToggleVariable,
        Action.ResetVariable, Action.If, Action.Fail, Action.Emit, Action.Cooldown {

    record SetVariable(Identifier key, Operand value) implements Action {
        public SetVariable {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(value, "value");
        }
    }

    record AddVariable(Identifier key, double amount) implements Action {
        public AddVariable {
            Objects.requireNonNull(key, "key");
            if (!Double.isFinite(amount)) {
                throw new IllegalArgumentException("amount must be finite");
            }
        }
    }

    record ToggleVariable(Identifier key) implements Action {
        public ToggleVariable {
            Objects.requireNonNull(key, "key");
        }
    }

    record ResetVariable(Identifier key) implements Action {
        public ResetVariable {
            Objects.requireNonNull(key, "key");
        }
    }

    record If(Condition condition, List<Action> then, List<Action> otherwise) implements Action {
        public If {
            Objects.requireNonNull(condition, "condition");
            then = List.copyOf(then);
            otherwise = List.copyOf(otherwise);
        }
    }

    /**
     * Passes when at least {@code seconds} have elapsed since the time stored in
     * the number variable {@code key}, and then stores the current time. Otherwise
     * the chain fails with {@code fail}, where {@code <remaining>} is replaced by
     * the remaining time. The variable's scope decides whether the cooldown is per
     * session, per player or global, and whether it survives a restart.
     */
    record Cooldown(Identifier key, long seconds, String fail) implements Action {
        public static final long MAX_SECONDS = 366L * 24 * 3600;

        public Cooldown {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(fail, "fail");
            if (seconds < 1 || seconds > MAX_SECONDS) {
                throw new IllegalArgumentException("seconds out of range");
            }
        }
    }

    /** Stops the chain and discards every staged change. */
    record Fail(String reason) implements Action {
        public Fail {
            Objects.requireNonNull(reason, "reason");
        }
    }

    /** Requests a side effect, such as a message, performed only after a successful commit. */
    record Emit(String type, Map<String, String> arguments) implements Action {
        public Emit {
            Objects.requireNonNull(type, "type");
            arguments = Map.copyOf(arguments);
        }
    }
}
