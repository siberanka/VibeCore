package com.siberanka.vibecore.logic;

import com.siberanka.vibecore.core.validation.Result;

/** Answers {@link Condition.Fact} checks for one viewer, for example from an economy or inventory. */
@FunctionalInterface
public interface Facts {

    /** Rejects every fact; used where no outside world is available. */
    Facts NONE = fact -> Result.rejected("condition", "unsupported-fact", "'" + fact.type() + "' is not available here");

    Result<Boolean> test(Condition.Fact fact);
}
