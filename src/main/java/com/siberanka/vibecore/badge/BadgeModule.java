package com.siberanka.vibecore.badge;

import com.siberanka.vibecore.core.module.ModuleId;
import com.siberanka.vibecore.core.module.VibeModule;
import com.siberanka.vibecore.core.validation.Problem;
import com.siberanka.vibecore.core.validation.Result;
import com.siberanka.vibecore.pack.PackSource;
import com.siberanka.vibecore.runtime.ModuleContext;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;

/**
 * Rank badges: named glyphs of the resource pack, listed and previewed with
 * every form needed to use them elsewhere. Badges never grant permissions.
 */
public final class BadgeModule implements VibeModule {

    private final ModuleContext context;
    private volatile BadgeCatalog catalog;

    public BadgeModule(ModuleContext context) {
        this.context = context;
    }

    @Override
    public ModuleId id() {
        return ModuleId.BADGE;
    }

    private Result<BadgeCatalog> load() {
        Result<PackSource> source = PackSource.load(context.plugin().getDataFolder().toPath());
        if (!source.isAccepted()) {
            return Result.rejected(source.problems());
        }
        Set<String> glyphs = source.orThrow().glyphs().keySet();
        return BadgeCatalog.load(context.plugin().getDataFolder().toPath(), glyphs);
    }

    @Override
    public void start() {
        Result<BadgeCatalog> loaded = load();
        if (!loaded.isAccepted()) {
            loaded.problems().forEach(problem -> context.plugin().getLogger().warning("  " + problem));
            throw new IllegalStateException("badge files are invalid");
        }
        catalog = loaded.orThrow();
    }

    @Override
    public void stop() {
    }

    @Override
    public List<Problem> reload() {
        Result<BadgeCatalog> loaded = load();
        if (!loaded.isAccepted()) {
            return loaded.problems();
        }
        catalog = loaded.orThrow();
        return List.of();
    }

    public BadgeCatalog catalog() {
        return catalog;
    }

    /** The badge picture, or empty when its glyph is not in the built pack yet. */
    public Optional<Component> picture(BadgeCatalog.Badge badge) {
        return context.glyphs().component(badge.glyph());
    }

    /**
     * Click-to-copy forms of a badge: the character itself, its Java escape,
     * its code point and the MiniMessage tag.
     */
    public Optional<Component> copyForms(BadgeCatalog.Badge badge) {
        return context.glyphs().codePoint(badge.glyph()).map(code -> {
            String character = new String(Character.toChars(code));
            String escape = String.format(Locale.ROOT, "\\u%04X", code);
            String point = String.format(Locale.ROOT, "U+%04X", code);
            String tag = "<glyph:" + badge.glyph() + ">";
            return Component.join(JoinConfiguration.separator(Component.text("  ")),
                    copy(character, context.glyphs().component(badge.glyph()).orElse(Component.text(character))),
                    copy(escape, Component.text(escape)), copy(point, Component.text(point)),
                    copy(tag, Component.text(tag)));
        });
    }

    private static Component copy(String value, Component label) {
        return label.color(NamedTextColor.WHITE)
                .clickEvent(ClickEvent.copyToClipboard(value))
                .hoverEvent(HoverEvent.showText(Component.text("Copy", NamedTextColor.GRAY)));
    }
}
