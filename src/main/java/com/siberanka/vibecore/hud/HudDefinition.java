package com.siberanka.vibecore.hud;

import com.siberanka.vibecore.logic.Condition;
import com.siberanka.vibecore.logic.Operand;
import com.siberanka.vibecore.project.Identifier;
import java.util.Objects;

/**
 * A persistent HUD. For every player the visible HUD is the first one, by
 * descending priority and then identifier, whose condition holds, unless an
 * operator chose one manually.
 */
public record HudDefinition(Identifier id, int priority, Condition when, int refreshTicks,
                            String actionbar, Bar bossbar) {

    /** Boss bar part of a HUD; progress is value divided by max, shown between 0 and 1. */
    public record Bar(String text, String color, String overlay, Operand value, Operand max) {
        public Bar {
            Objects.requireNonNull(text, "text");
            Objects.requireNonNull(color, "color");
            Objects.requireNonNull(overlay, "overlay");
        }
    }

    public HudDefinition {
        Objects.requireNonNull(id, "id");
    }
}
