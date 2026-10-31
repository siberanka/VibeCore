package com.siberanka.vibecore.core.config;

import com.siberanka.vibecore.core.module.ModuleId;
import com.siberanka.vibecore.core.text.MessageCatalog;
import com.siberanka.vibecore.core.validation.MapReader;
import com.siberanka.vibecore.core.validation.Result;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Validates a complete configuration candidate. The parser has no side
 * effects; activation happens only after an accepted result.
 */
public final class SettingsParser {

    private static final Set<String> ROOT_KEYS = Set.of("config-version", "locale", "modules", "workers");
    private static final Set<String> WORKER_KEYS = Set.of("threads", "queue-capacity");

    private SettingsParser() {
    }

    public static Result<CoreSettings> parse(Map<String, ?> document) {
        MapReader root = MapReader.root(document);
        root.rejectUnknownKeys(ROOT_KEYS);

        if (!root.has("config-version")) {
            root.report("config-version", "missing", "is required");
        }
        int version = root.integer("config-version", CoreSettings.CURRENT_VERSION, 1, Integer.MAX_VALUE);
        if (version > CoreSettings.CURRENT_VERSION) {
            root.report("config-version", "future-version",
                    "was written by a newer VibeCore (" + version + " > " + CoreSettings.CURRENT_VERSION + ")");
        }

        String locale = root.text("locale", MessageCatalog.FALLBACK_LOCALE, 16);
        if (!MessageCatalog.SUPPORTED_LOCALES.contains(locale)) {
            root.report("locale", "unsupported", "must be one of " + MessageCatalog.SUPPORTED_LOCALES);
            locale = MessageCatalog.FALLBACK_LOCALE;
        }

        MapReader modules = root.section("modules");
        modules.rejectUnknownKeys(Arrays.stream(ModuleId.values()).map(ModuleId::key)
                .collect(Collectors.toUnmodifiableSet()));
        EnumSet<ModuleId> enabled = EnumSet.noneOf(ModuleId.class);
        for (ModuleId id : ModuleId.values()) {
            if (modules.bool(id.key(), false)) {
                enabled.add(id);
            }
        }

        MapReader workers = root.section("workers");
        workers.rejectUnknownKeys(WORKER_KEYS);
        int threads = workers.integer("threads", 2, 1, 16);
        int queue = workers.integer("queue-capacity", 64, 1, 4096);

        if (root.hasProblems()) {
            return Result.rejected(root.problems());
        }
        return Result.accepted(new CoreSettings(version, locale, enabled,
                new CoreSettings.WorkerSettings(threads, queue)));
    }
}
