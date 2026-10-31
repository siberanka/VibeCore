package com.siberanka.vibecore.core.config;

import com.siberanka.vibecore.core.module.ModuleId;
import com.siberanka.vibecore.core.text.MessageCatalog;
import com.siberanka.vibecore.core.validation.MapReader;
import com.siberanka.vibecore.core.validation.Result;
import com.siberanka.vibecore.menu.CommandTemplate;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * Validates a complete configuration candidate. The parser has no side
 * effects; activation happens only after an accepted result.
 */
public final class SettingsParser {

    private static final Set<String> ROOT_KEYS = Set.of("config-version", "locale", "modules", "workers", "actions", "pack");
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

        MapReader actions = root.section("actions");
        actions.rejectUnknownKeys(Set.of("allowed-commands"));
        Set<String> allowedCommands = new TreeSet<>();
        Object rawCommands = document.get("actions") instanceof Map<?, ?> section ? section.get("allowed-commands") : null;
        if (rawCommands instanceof List<?> list) {
            for (Object entry : list) {
                String command = entry instanceof String text ? text.toLowerCase(Locale.ROOT) : null;
                if (command == null || !command.matches("[a-z0-9_.-]+(:[a-z0-9_.-]+)?") || command.length() > 64) {
                    actions.report("allowed-commands", "format", "entries must be command names such as give or eco");
                } else if (CommandTemplate.FORBIDDEN.contains(command)) {
                    actions.report("allowed-commands", "forbidden", "'" + command + "' can never run from a menu");
                } else {
                    allowedCommands.add(command);
                }
            }
        } else if (rawCommands != null) {
            actions.report("allowed-commands", "type", "expected a list of command names");
        }

        MapReader pack = root.section("pack");
        pack.rejectUnknownKeys(Set.of("url", "send-on-join", "required", "on-decline"));
        String url = pack.text("url", "", 512).trim();
        if (!url.isEmpty() && !url.matches("https://\\S+")) {
            pack.report("url", "format", "must be an https:// address without spaces");
        }
        String onDecline = pack.text("on-decline", "keep", 16);
        if (!Set.of("keep", "kick").contains(onDecline)) {
            pack.report("on-decline", "format", "must be keep or kick");
        }
        CoreSettings.PackDelivery delivery = new CoreSettings.PackDelivery(url,
                pack.bool("send-on-join", false), pack.bool("required", false), onDecline.equals("kick"));

        if (root.hasProblems()) {
            return Result.rejected(root.problems());
        }
        return Result.accepted(new CoreSettings(version, locale, enabled,
                new CoreSettings.WorkerSettings(threads, queue), allowedCommands, delivery));
    }
}
