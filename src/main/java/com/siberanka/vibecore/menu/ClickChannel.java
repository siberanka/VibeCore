package com.siberanka.vibecore.menu;

import java.util.Optional;

/** Click kinds an item can react to. Every other inventory interaction is blocked. */
public enum ClickChannel {
    LEFT("left"),
    RIGHT("right"),
    SHIFT_LEFT("shift-left"),
    SHIFT_RIGHT("shift-right"),
    MIDDLE("middle");

    private final String key;

    ClickChannel(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }

    public static Optional<ClickChannel> fromKey(String key) {
        for (ClickChannel channel : values()) {
            if (channel.key.equals(key)) {
                return Optional.of(channel);
            }
        }
        return Optional.empty();
    }
}
