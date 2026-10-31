package com.siberanka.vibecore.core.module;

import java.util.Objects;

/** Snapshot of a module's state and the reason for it. */
public record ModuleStatus(ModuleId id, ModuleState state, boolean experimental, String reason) {

    public ModuleStatus {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(reason, "reason");
    }
}
