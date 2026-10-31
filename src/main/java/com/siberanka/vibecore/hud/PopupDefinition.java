package com.siberanka.vibecore.hud;

import com.siberanka.vibecore.project.Identifier;
import java.util.Objects;

/**
 * A short notification shown for {@code millis}, either as a title with an
 * optional subtitle or on the action bar.
 */
public record PopupDefinition(Identifier id, Surface surface, String text, String subtitle, long millis) {

    public static final long MIN_MILLIS = 500;
    public static final long MAX_MILLIS = 600_000;

    public enum Surface {
        TITLE, ACTIONBAR
    }

    public PopupDefinition {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(surface, "surface");
        Objects.requireNonNull(text, "text");
        if (millis < MIN_MILLIS || millis > MAX_MILLIS) {
            throw new IllegalArgumentException("duration out of range");
        }
    }
}
