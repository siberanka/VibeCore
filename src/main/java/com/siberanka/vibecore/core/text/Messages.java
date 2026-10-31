package com.siberanka.vibecore.core.text;

import java.util.Locale;
import java.util.Objects;
import java.util.function.Supplier;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * Renders catalog templates with MiniMessage. Arguments are inserted as
 * unparsed literal text, so player-controlled values can never become
 * formatting, click or command tags.
 */
public final class Messages {

    private final MiniMessage miniMessage = MiniMessage.miniMessage();
    private final Supplier<MessageCatalog> catalog;
    private final Supplier<String> defaultLocale;

    public Messages(Supplier<MessageCatalog> catalog, Supplier<String> defaultLocale) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.defaultLocale = Objects.requireNonNull(defaultLocale, "defaultLocale");
    }

    public String localeOf(CommandSender sender) {
        if (sender instanceof Player player) {
            Locale locale = player.locale();
            return MessageCatalog.resolveLocale(locale.toLanguageTag(), defaultLocale.get());
        }
        return MessageCatalog.resolveLocale(null, defaultLocale.get());
    }

    /** Renders a message; {@code arguments} alternate placeholder names and values. */
    public Component render(String locale, String key, String... arguments) {
        return render(locale, key, TagResolver.empty(), arguments);
    }

    /**
     * Renders a message with additional component placeholders, for values
     * that are themselves rendered from trusted templates.
     */
    public Component render(String locale, String key, TagResolver components, String... arguments) {
        if (arguments.length % 2 != 0) {
            throw new IllegalArgumentException("Arguments must be name/value pairs");
        }
        TagResolver.Builder resolver = TagResolver.builder().resolver(components);
        for (int index = 0; index < arguments.length; index += 2) {
            resolver.resolver(Placeholder.unparsed(arguments[index],
                    Objects.requireNonNullElse(arguments[index + 1], "")));
        }
        return miniMessage.deserialize(catalog.get().template(locale, key), resolver.build());
    }

    /** Sends a prefixed message, for standalone notifications. */
    public void send(CommandSender sender, String key, String... arguments) {
        String locale = localeOf(sender);
        Audience audience = sender;
        audience.sendMessage(render(locale, "prefix").append(render(locale, key, arguments)));
    }

    /** Sends a message without the prefix, for lines of a multi-line listing. */
    public void sendLine(CommandSender sender, String key, String... arguments) {
        Audience audience = sender;
        audience.sendMessage(render(localeOf(sender), key, arguments));
    }
}
