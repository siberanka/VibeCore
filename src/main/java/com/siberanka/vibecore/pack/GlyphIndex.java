package com.siberanka.vibecore.pack;

import java.util.Map;
import java.util.Optional;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;

/**
 * Glyphs of the most recently built pack, shared with modules that render
 * text. A glyph renders in its own font, so it never replaces a character of
 * the default font.
 */
public final class GlyphIndex {

    private static final Key FONT = Key.key(PackCompiler.NAMESPACE, "glyphs");
    private volatile Map<String, Integer> glyphs = Map.of();

    public void update(Map<String, Integer> glyphs) {
        this.glyphs = Map.copyOf(glyphs);
    }

    public int size() {
        return glyphs.size();
    }

    /** The code point of a built glyph. */
    public Optional<Integer> codePoint(String id) {
        return Optional.ofNullable(glyphs.get(id));
    }

    public Optional<Component> component(String id) {
        Integer code = glyphs.get(id);
        return code == null ? Optional.empty()
                : Optional.of(Component.text(new String(Character.toChars(code))).font(FONT));
    }
}
