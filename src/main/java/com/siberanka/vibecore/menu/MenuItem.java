package com.siberanka.vibecore.menu;

import com.siberanka.vibecore.logic.Action;
import com.siberanka.vibecore.logic.Condition;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A button: base look, ordered state overrides (the first matching state
 * wins), an optional visibility condition and per-click action chains.
 *
 * <p>Visibility and state only change what is shown. Click actions re-check
 * their own conditions when they run, so a stale display never grants
 * anything.
 */
public record MenuItem(
        String key,
        List<Integer> slots,
        ItemLook base,
        Condition visible,
        List<State> states,
        Map<ClickChannel, List<Action>> actions) {

    /** Look override applied while its condition holds. */
    public record State(Condition when, ItemLook look) {
        public State {
            Objects.requireNonNull(when, "when");
            Objects.requireNonNull(look, "look");
        }
    }

    public MenuItem {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(base, "base");
        slots = List.copyOf(slots);
        states = List.copyOf(states);
        Map<ClickChannel, List<Action>> copy = new EnumMap<>(ClickChannel.class);
        actions.forEach((channel, chain) -> copy.put(channel, List.copyOf(chain)));
        actions = copy.isEmpty() ? Map.of() : Map.copyOf(copy);
    }

    public List<Action> actionsFor(ClickChannel channel) {
        return actions.getOrDefault(channel, List.of());
    }
}
