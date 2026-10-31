package com.siberanka.vibecore.logic;

import com.siberanka.vibecore.core.validation.Problem;
import java.util.List;

/** Typed result of running an action chain. */
public sealed interface Outcome permits Outcome.Committed, Outcome.Failed, Outcome.Rejected {

    /** Every staged change was applied; the effects may now be performed in order. */
    record Committed(List<Action.Emit> effects, int writes, int steps) implements Outcome {
        public Committed {
            effects = List.copyOf(effects);
        }
    }

    /** The chain reached a {@link Action.Fail}; nothing was applied. */
    record Failed(String reason, int steps) implements Outcome {
    }

    /** The chain was invalid at run time or hit a limit; nothing was applied. */
    record Rejected(List<Problem> problems, int steps) implements Outcome {
        public Rejected {
            problems = List.copyOf(problems);
        }
    }
}
