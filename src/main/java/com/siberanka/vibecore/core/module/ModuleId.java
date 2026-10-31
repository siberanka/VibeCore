package com.siberanka.vibecore.core.module;

import java.util.Locale;
import java.util.Optional;

/** Feature areas that can be switched on and off independently. */
public enum ModuleId {
    MENU("menu"),
    CURSOR("cursor"),
    HUD("hud"),
    HOLOGRAM("hologram"),
    BADGE("badge"),
    PACK("pack"),
    EDITOR("editor"),
    BEDROCK("bedrock");

    private final String key;

    ModuleId(String key) {
        this.key = key;
    }

    /** Stable configuration and command key. */
    public String key() {
        return key;
    }

    public static Optional<ModuleId> fromKey(String key) {
        String normalized = key.toLowerCase(Locale.ROOT);
        for (ModuleId id : values()) {
            if (id.key.equals(normalized)) {
                return Optional.of(id);
            }
        }
        return Optional.empty();
    }
}
