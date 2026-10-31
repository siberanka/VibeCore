package com.siberanka.vibecore.core.diagnostic;

import com.siberanka.vibecore.core.config.ActiveState;
import com.siberanka.vibecore.core.config.CoreState;
import com.siberanka.vibecore.core.module.ModuleRegistry;
import com.siberanka.vibecore.core.module.ModuleState;
import com.siberanka.vibecore.core.module.ModuleStatus;
import com.siberanka.vibecore.core.task.BoundedWorkers;
import com.siberanka.vibecore.core.task.TaskTracker;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;
import org.bukkit.Server;

/**
 * Collects a redacted runtime snapshot for troubleshooting. Only versions,
 * counters and states are reported: no file paths, addresses, tokens or
 * player data.
 */
public final class Diagnostics {

    private final String pluginVersion;
    private final Server server;
    private final ActiveState<CoreState> state;
    private final ModuleRegistry modules;
    private final BoundedWorkers workers;
    private final TaskTracker tasks;

    public Diagnostics(String pluginVersion, Server server, ActiveState<CoreState> state,
                       ModuleRegistry modules, BoundedWorkers workers, TaskTracker tasks) {
        this.pluginVersion = pluginVersion;
        this.server = server;
        this.state = state;
        this.modules = modules;
        this.workers = workers;
        this.tasks = tasks;
    }

    public Map<String, String> snapshot() {
        Map<String, String> entries = new LinkedHashMap<>();
        var revision = state.revision();
        entries.put("vibecore", pluginVersion);
        entries.put("server", server.getName() + " " + server.getMinecraftVersion());
        entries.put("java", Runtime.version().toString());
        entries.put("config-revision", Long.toString(revision.revision()));
        entries.put("default-locale", revision.value().settings().defaultLocale());

        Map<ModuleState, Integer> counts = new EnumMap<>(ModuleState.class);
        for (ModuleStatus status : modules.statuses()) {
            counts.merge(status.state(), 1, Integer::sum);
        }
        entries.put("modules", counts.entrySet().stream()
                .map(entry -> entry.getKey().name().toLowerCase(java.util.Locale.ROOT).replace('_', '-')
                        + "=" + entry.getValue())
                .collect(Collectors.joining(", ")));

        var configured = revision.value().settings().workers();
        entries.put("workers", workers.threads() + " threads, " + workers.active() + " active, "
                + workers.queued() + "/" + workers.queueCapacity() + " queued");
        if (configured.threads() != workers.threads() || configured.queueCapacity() != workers.queueCapacity()) {
            entries.put("workers-pending", configured.threads() + " threads, "
                    + configured.queueCapacity() + " queue after restart");
        }
        entries.put("scheduled-tasks", Integer.toString(tasks.active()));
        return entries;
    }
}
