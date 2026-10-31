package com.siberanka.vibecore.hologram;

import com.siberanka.vibecore.logic.Condition;
import com.siberanka.vibecore.project.Identifier;
import java.util.List;
import java.util.Objects;

/**
 * What a hologram shows, independent of where it is placed. One design can
 * be placed many times; changing the design changes every placement.
 */
public record HologramDesign(Identifier id, List<Slide> slides, Billboard billboard, double viewRange,
                             float scale, boolean shadow, Integer background, Condition visible) {

    /** One page of text lines, shown for {@code millis} before the next. */
    public record Slide(List<String> lines, long millis) {
        public Slide {
            lines = List.copyOf(lines);
        }
    }

    /** How the hologram turns towards the viewer. */
    public enum Billboard {
        CENTER, VERTICAL, HORIZONTAL, FIXED
    }

    public HologramDesign {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(billboard, "billboard");
        slides = List.copyOf(slides);
        if (slides.isEmpty()) {
            throw new IllegalArgumentException("a design needs at least one slide");
        }
    }

    public long cycleMillis() {
        return slides.stream().mapToLong(Slide::millis).sum();
    }

    /** The slide shown at {@code millis} into the endless cycle. */
    public Slide slideAt(long millis) {
        long cycle = cycleMillis();
        long position = cycle == 0 ? 0 : Math.floorMod(millis, cycle);
        for (Slide slide : slides) {
            if (position < slide.millis()) {
                return slide;
            }
            position -= slide.millis();
        }
        return slides.getLast();
    }
}
