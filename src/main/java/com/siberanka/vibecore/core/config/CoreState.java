package com.siberanka.vibecore.core.config;

import com.siberanka.vibecore.core.text.MessageCatalog;
import java.util.Objects;

/** Settings and messages that are validated and activated together. */
public record CoreState(CoreSettings settings, MessageCatalog messages) {

    public CoreState {
        Objects.requireNonNull(settings, "settings");
        Objects.requireNonNull(messages, "messages");
    }
}
