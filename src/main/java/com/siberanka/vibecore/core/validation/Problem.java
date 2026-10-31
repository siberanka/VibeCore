package com.siberanka.vibecore.core.validation;

import java.util.Objects;

/**
 * A single validation finding. The path addresses the offending value in the
 * candidate document (for example {@code workers.threads}); the code is a
 * stable machine-readable identifier suitable for diagnostics and tests.
 */
public record Problem(String path, String code, String detail) {

    public Problem {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(detail, "detail");
    }

    @Override
    public String toString() {
        return (path.isEmpty() ? "<root>" : path) + ": " + detail + " [" + code + "]";
    }
}
