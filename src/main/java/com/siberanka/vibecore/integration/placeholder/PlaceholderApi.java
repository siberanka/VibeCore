package com.siberanka.vibecore.integration.placeholder;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Optional;
import java.util.regex.Pattern;
import org.bukkit.OfflinePlayer;
import org.bukkit.Server;
import org.bukkit.plugin.Plugin;

/**
 * Optional PlaceholderAPI text expansion, looked up at run time so VibeCore
 * neither bundles nor requires PlaceholderAPI. Without it, placeholders
 * resolve to nothing and the rest of the text is unaffected.
 */
public final class PlaceholderApi {

    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z0-9_.:-]{1,64}");
    private static final int MAX_RESULT = 256;

    private final Server server;
    private MethodHandle setPlaceholders;
    private Plugin resolvedFor;

    public PlaceholderApi(Server server) {
        this.server = server;
    }

    public static boolean isValidIdentifier(String identifier) {
        return identifier != null && IDENTIFIER.matcher(identifier).matches();
    }

    /**
     * Expands {@code %identifier%} for a player.
     *
     * @return the expanded text, at most 256 characters, or empty when PlaceholderAPI is unavailable,
     *         the identifier is invalid or the expansion fails
     */
    public Optional<String> expand(OfflinePlayer player, String identifier) {
        if (!isValidIdentifier(identifier)) {
            return Optional.empty();
        }
        MethodHandle handle = handle();
        if (handle == null) {
            return Optional.empty();
        }
        try {
            Object result = handle.invoke(player, "%" + identifier + "%");
            if (!(result instanceof String text)) {
                return Optional.empty();
            }
            return Optional.of(text.length() > MAX_RESULT ? text.substring(0, MAX_RESULT) : text);
        } catch (Throwable failure) {
            return Optional.empty();
        }
    }

    private MethodHandle handle() {
        Plugin plugin = server.getPluginManager().getPlugin("PlaceholderAPI");
        if (plugin == null || !plugin.isEnabled()) {
            return null;
        }
        if (plugin != resolvedFor) {
            resolvedFor = plugin;
            try {
                Class<?> api = Class.forName("me.clip.placeholderapi.PlaceholderAPI", true,
                        plugin.getClass().getClassLoader());
                setPlaceholders = MethodHandles.publicLookup().findStatic(api, "setPlaceholders",
                        MethodType.methodType(String.class, OfflinePlayer.class, String.class));
            } catch (ReflectiveOperationException | LinkageError incompatible) {
                setPlaceholders = null;
            }
        }
        return setPlaceholders;
    }
}
