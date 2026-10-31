package com.siberanka.vibecore.variable;

import java.util.Locale;
import java.util.Optional;

/** Value types supported by project variables. */
public enum VariableType {
    NUMBER("number"),
    BOOLEAN("boolean"),
    TEXT("text");

    private final String key;

    VariableType(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }

    public static Optional<VariableType> fromKey(String key) {
        String normalized = key.toLowerCase(Locale.ROOT);
        for (VariableType type : values()) {
            if (type.key.equals(normalized)) {
                return Optional.of(type);
            }
        }
        return Optional.empty();
    }
}
