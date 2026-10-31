package com.siberanka.vibecore.runtime;

import com.siberanka.vibecore.core.validation.Result;
import com.siberanka.vibecore.integration.placeholder.PlaceholderApi;
import com.siberanka.vibecore.pack.GlyphIndex;
import com.siberanka.vibecore.project.Identifier;
import com.siberanka.vibecore.variable.VariableContext;
import com.siberanka.vibecore.variable.VariableValue;
import com.siberanka.vibecore.world.PlayerWorld;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.Tag;
import net.kyori.adventure.text.minimessage.tag.resolver.ArgumentQueue;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.entity.Player;

/**
 * Renders owner-written MiniMessage text for one viewer. Besides formatting
 * it resolves {@code <player>}, {@code <var:key>}, {@code <data:name>},
 * {@code <papi:identifier>} and {@code <glyph:id>}. Every inserted value is
 * plain text, so values can never add click, hover or other tags.
 */
public final class ViewerText {

    private final MiniMessage miniMessage = MiniMessage.miniMessage();
    private final VariableService variables;
    private final GlyphIndex glyphs;
    private final PlaceholderApi placeholders;

    public ViewerText(VariableService variables, GlyphIndex glyphs, PlaceholderApi placeholders) {
        this.variables = variables;
        this.glyphs = glyphs;
        this.placeholders = placeholders;
    }

    private static String joined(ArgumentQueue arguments) {
        StringBuilder text = new StringBuilder();
        while (arguments.hasNext()) {
            text.append(text.isEmpty() ? "" : ":").append(arguments.pop().value());
        }
        return text.toString();
    }

    /**
     * @param session the open screen session whose session variables are visible, or null
     */
    public Component render(String template, Player viewer, PlayerWorld world, UUID session) {
        VariableContext context = VariableContext.session(viewer.getUniqueId(), session);
        TagResolver variable = TagResolver.resolver("var", (arguments, ignored) -> {
            Result<Identifier> id = Identifier.parse("var", joined(arguments));
            Result<VariableValue> value = id.isAccepted() ? variables.store().get(id.orThrow(), context)
                    : Result.rejected(id.problems());
            return Tag.inserting(Component.text(value.isAccepted() ? value.orThrow().canonical() : "?"));
        });
        TagResolver data = TagResolver.resolver("data", (arguments, ignored) -> {
            Result<VariableValue> value = world.data(joined(arguments));
            return Tag.inserting(Component.text(value.isAccepted() ? value.orThrow().canonical() : "?"));
        });
        TagResolver papi = TagResolver.resolver("papi", (arguments, ignored) -> Tag.inserting(
                LegacyComponentSerializer.legacySection().deserialize(
                        placeholders.expand(viewer, joined(arguments)).orElse(""))));
        TagResolver glyph = TagResolver.resolver("glyph", (arguments, ignored) -> Tag.selfClosingInserting(
                glyphs.component(joined(arguments)).orElse(Component.empty())));
        return miniMessage.deserialize(template, variable, data, papi, glyph,
                Placeholder.unparsed("player", viewer.getName()));
    }
}
