package com.siberanka.vibecore.logic;

import com.siberanka.vibecore.core.validation.Result;
import java.util.List;

/**
 * External effects of a chain, such as money and items, performed together
 * with its variable changes.
 *
 * <p>{@link #perform} runs after the chain staged successfully and before the
 * variable changes are applied. It must either perform every effect it is
 * responsible for or none of them. If the variable changes then fail to apply,
 * the engine calls {@link Undo#undo()}.
 */
@FunctionalInterface
public interface SideEffects {

    /** Problem code for an unmet business rule; reported as {@link Outcome.Failed} with the detail as reason. */
    String UNMET = "unmet";

    SideEffects NONE = effects -> Result.accepted(() -> {
    });

    Result<Undo> perform(List<Action.Emit> effects);

    /** Reverts performed effects. */
    @FunctionalInterface
    interface Undo {
        void undo();
    }
}
