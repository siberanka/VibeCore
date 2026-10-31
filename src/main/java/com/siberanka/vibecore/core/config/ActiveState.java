package com.siberanka.vibecore.core.config;

import com.siberanka.vibecore.core.validation.Result;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Holds the active value of a reloadable state together with a monotonically
 * increasing revision. Only accepted candidates replace the active value, so
 * a rejected reload leaves the previous working state untouched.
 */
public final class ActiveState<T> {

    /** Immutable pair of a value and the revision it was activated at. */
    public record Revision<T>(long revision, T value) {
    }

    private final AtomicReference<Revision<T>> current;

    public ActiveState(T initial) {
        current = new AtomicReference<>(new Revision<>(1, Objects.requireNonNull(initial, "initial")));
    }

    public T get() {
        return current.get().value();
    }

    public Revision<T> revision() {
        return current.get();
    }

    /**
     * Activates the candidate when it was accepted.
     *
     * @return the same result, so callers can report problems of a rejection
     */
    public Result<T> offer(Result<T> candidate) {
        if (candidate.isAccepted()) {
            T value = candidate.orThrow();
            current.updateAndGet(previous -> new Revision<>(previous.revision() + 1, value));
        }
        return candidate;
    }
}
