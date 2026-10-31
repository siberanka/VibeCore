package com.siberanka.vibecore.variable;

import java.util.Locale;
import java.util.Optional;

/**
 * Lifetime and ownership of a variable value. The scopes are deliberately
 * distinct: screen-session data, data that lives while a player is online, and
 * data written to disk must never be confused with one another.
 */
public enum VariableScope {
    /** One open screen session; discarded when the screen closes. */
    SESSION("session", true, false),
    /** One player while online; discarded on disconnect. */
    ONLINE_PLAYER("online-player", true, false),
    /** One player, stored on disk across restarts. */
    PERSISTENT_PLAYER("persistent-player", true, true),
    /** One value for the whole server, stored on disk. */
    PERSISTENT_GLOBAL("persistent-global", false, true);

    private final String key;
    private final boolean playerBound;
    private final boolean persistent;

    VariableScope(String key, boolean playerBound, boolean persistent) {
        this.key = key;
        this.playerBound = playerBound;
        this.persistent = persistent;
    }

    public String key() {
        return key;
    }

    /** Whether every read and write names a subject player. */
    public boolean playerBound() {
        return playerBound;
    }

    /** Whether values survive a restart. */
    public boolean persistent() {
        return persistent;
    }

    public static Optional<VariableScope> fromKey(String key) {
        String normalized = key.toLowerCase(Locale.ROOT);
        for (VariableScope scope : values()) {
            if (scope.key.equals(normalized)) {
                return Optional.of(scope);
            }
        }
        return Optional.empty();
    }
}
