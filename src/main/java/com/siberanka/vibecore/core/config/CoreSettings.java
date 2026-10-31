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
        Set<String> allowedCommands,
        PackDelivery packDelivery) {

    public static final int CURRENT_VERSION = 1;

    public CoreSettings {
        Objects.requireNonNull(defaultLocale, "defaultLocale");
        Objects.requireNonNull(workers, "workers");
        enabledModules = enabledModules.isEmpty()
                ? Set.of() : Set.copyOf(EnumSet.copyOf(enabledModules));
        allowedCommands = Set.copyOf(allowedCommands);
    }

    /**
     * How the built pack reaches players.
     *
     * @param url        HTTPS address that serves exactly the built packs/vibecore-java.zip, or empty
     * @param sendOnJoin offer the pack when a player joins
     * @param required   ask the client to treat the pack as required
     * @param kickOnDecline remove players who decline or cannot load the pack
     */
    public record PackDelivery(String url, boolean sendOnJoin, boolean required, boolean kickOnDecline) {
        public static final PackDelivery OFF = new PackDelivery("", false, false, false);
    }

    /** Bounds for the background worker pool used by file, media and pack work. */
    public record WorkerSettings(int threads, int queueCapacity) {
    }
}
