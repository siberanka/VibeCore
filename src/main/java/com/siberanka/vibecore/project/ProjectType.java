package com.siberanka.vibecore.project;

import java.util.Locale;
import java.util.Optional;

/** Kinds of project that share the workspace, asset registry and logic engine. */
public enum ProjectType {
    INVENTORY_MENU("inventory-menu"),
    CURSOR_SCENE("cursor-scene"),
    HUD("hud"),
    HOLOGRAM("hologram"),
    BADGE("badge");

    private final String key;

    ProjectType(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }

    public static Optional<ProjectType> fromKey(String key) {
        String normalized = key.toLowerCase(Locale.ROOT);
        for (ProjectType type : values()) {
            if (type.key.equals(normalized)) {
                return Optional.of(type);
            }
        }
        return Optional.empty();
    }
}
