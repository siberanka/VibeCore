package com.siberanka.vibecore.core.config;

import com.siberanka.vibecore.core.module.ModuleId;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/** Immutable, validated core configuration. */
public record CoreSettings(
        int configVersion,
        String defaultLocale,
        Set<ModuleId> enabledModules,
        WorkerSettings workers,
        Set<String> allowedCommands) {

    public static final int CURRENT_VERSION = 1;

    public CoreSettings {
        Objects.requireNonNull(defaultLocale, "defaultLocale");
        Objects.requireNonNull(workers, "workers");
        enabledModules = enabledModules.isEmpty()
                ? Set.of() : Set.copyOf(EnumSet.copyOf(enabledModules));
        allowedCommands = Set.copyOf(allowedCommands);
    }

    /** Bounds for the background worker pool used by file, media and pack work. */
    public record WorkerSettings(int threads, int queueCapacity) {
    }
}
