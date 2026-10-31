package com.siberanka.vibecore.menu;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.siberanka.vibecore.core.validation.Problem;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Player head sources: {@code viewer} (the player looking at the menu), a
 * player UUID, or a skin texture value. Texture values are accepted only when
 * they decode to a skin hosted on the official texture server, so a menu file
 * cannot make clients fetch images from arbitrary hosts. Names are not
 * accepted because resolving them needs a blocking lookup.
 */
public final class HeadSpec {

    public static final String VIEWER = "viewer";
    private static final int MAX_TEXTURE_LENGTH = 4096;
    private static final Pattern TEXTURE_URL = Pattern.compile("https?://textures\\.minecraft\\.net/texture/[0-9a-f]{32,128}");

    private HeadSpec() {
    }

    /** Returns the normalized head value, or null after recording a problem. */
    static String validate(String path, Object raw, List<Problem> problems) {
        if (!(raw instanceof String text) || text.isEmpty()) {
            problems.add(new Problem(path, "type", "expected viewer, a player UUID or a texture value"));
            return null;
        }
        if (text.equals(VIEWER)) {
            return text;
        }
        if (uuid(text) != null) {
            return text.toLowerCase(Locale.ROOT);
        }
        if (textureUrl(text) != null) {
            return text;
        }
        problems.add(new Problem(path, "format",
                "expected viewer, a player UUID or a texture value pointing to textures.minecraft.net"));
        return null;
    }

    static UUID uuid(String text) {
        if (text.length() != 36) {
            return null;
        }
        try {
            return UUID.fromString(text);
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    /** The skin URL of a texture value, or null when it is not a valid official skin texture. */
    static String textureUrl(String value) {
        if (value.length() > MAX_TEXTURE_LENGTH) {
            return null;
        }
        try {
            String json = new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8);
            JsonElement root = JsonParser.parseString(json);
            JsonObject skin = root.getAsJsonObject().getAsJsonObject("textures").getAsJsonObject("SKIN");
            String url = skin.get("url").getAsString();
            return TEXTURE_URL.matcher(url).matches() ? url : null;
        } catch (RuntimeException invalid) {
            return null;
        }
    }
}
