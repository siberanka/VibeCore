package com.siberanka.vibecore.core.module;

import com.siberanka.vibecore.core.validation.Problem;
import java.util.List;
import java.util.Optional;

/** A feature module managed by {@link ModuleRegistry}. */
public interface VibeModule {

    ModuleId id();

    /** Experimental modules are reported as such in status and diagnostics. */
    default boolean experimental() {
        return false;
    }

    /**
     * Checks environmental requirements before start-up, such as an optional
     * plugin or a server capability.
     *
     * @return the reason the module cannot run here, or empty when it can
     */
    default Optional<String> unsupportedReason() {
        return Optional.empty();
    }

    /** Starts the module. A thrown exception marks only this module as failed. */
    void start() throws Exception;

    /** Releases every task, session and render state owned by the module. */
    void stop() throws Exception;

    /**
     * Validates and activates the module's own files while it keeps running.
     * A rejected candidate must leave the previous module state in effect.
     *
     * @return the problems of a rejected candidate, or an empty list
     */
    default List<Problem> reload() {
        return List.of();
    }
}
