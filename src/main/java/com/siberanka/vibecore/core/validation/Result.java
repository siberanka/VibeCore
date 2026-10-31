package com.siberanka.vibecore.core.validation;

import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * Either an accepted value or a non-empty list of problems. Candidates are
 * activated only from a successful result, so a rejected candidate can never
 * replace an active state.
 */
public sealed interface Result<T> permits Result.Accepted, Result.Rejected {

    static <T> Result<T> accepted(T value) {
        return new Accepted<>(value);
    }

    static <T> Result<T> rejected(List<Problem> problems) {
        return new Rejected<>(problems);
    }

    static <T> Result<T> rejected(String path, String code, String detail) {
        return new Rejected<>(List.of(new Problem(path, code, detail)));
    }

    boolean isAccepted();

    /** Returns the value or throws if the result was rejected. */
    T orThrow();

    List<Problem> problems();

    <R> Result<R> map(Function<? super T, ? extends R> mapper);

    record Accepted<T>(T value) implements Result<T> {
        public Accepted {
            Objects.requireNonNull(value, "value");
        }

        @Override
        public boolean isAccepted() {
            return true;
        }

        @Override
        public T orThrow() {
            return value;
        }

        @Override
        public List<Problem> problems() {
            return List.of();
        }

        @Override
        public <R> Result<R> map(Function<? super T, ? extends R> mapper) {
            return new Accepted<>(mapper.apply(value));
        }
    }

    record Rejected<T>(List<Problem> problems) implements Result<T> {
        public Rejected {
            problems = List.copyOf(problems);
            if (problems.isEmpty()) {
                throw new IllegalArgumentException("A rejected result needs at least one problem");
            }
        }

        @Override
        public boolean isAccepted() {
            return false;
        }

        @Override
        public T orThrow() {
            throw new IllegalStateException("Rejected: " + problems);
        }

        @Override
        public <R> Result<R> map(Function<? super T, ? extends R> mapper) {
            return new Rejected<>(problems);
        }
    }
}
