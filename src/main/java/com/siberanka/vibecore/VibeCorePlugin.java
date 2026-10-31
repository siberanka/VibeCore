package com.siberanka.vibecore;

import com.siberanka.vibecore.command.VibeCoreCommand;
import com.siberanka.vibecore.core.config.ActiveState;
import com.siberanka.vibecore.core.config.CoreSettings;
import com.siberanka.vibecore.core.config.CoreState;
import com.siberanka.vibecore.core.config.CoreStateLoader;
import com.siberanka.vibecore.core.diagnostic.Diagnostics;
import com.siberanka.vibecore.core.module.ModuleRegistry;
import com.siberanka.vibecore.core.module.ModuleState;
import com.siberanka.vibecore.core.task.BoundedWorkers;
import com.siberanka.vibecore.core.task.TaskTracker;
import com.siberanka.vibecore.core.text.MessageCatalog;
import com.siberanka.vibecore.core.text.Messages;
import com.siberanka.vibecore.core.validation.Problem;
import com.siberanka.vibecore.core.validation.Result;
import com.siberanka.vibecore.menu.MenuModule;
import java.io.File;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/** Plugin entry point: owns the core services and their lifecycle. */
public final class VibeCorePlugin extends JavaPlugin {

    private static final int PROBLEMS_SHOWN_IN_CHAT = 8;

    private final AtomicBoolean reloading = new AtomicBoolean();
    private CoreStateLoader loader;
    private ActiveState<CoreState> state;
    private ModuleRegistry modules;
    private TaskTracker tasks;
    private BoundedWorkers workers;
    private Diagnostics diagnostics;
    private Messages messages;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        for (String resource : List.of("variables.yml", "menus/example.yml")) {
            if (!new File(getDataFolder(), resource).exists()) {
                saveResource(resource, false);
            }
        }
        loader = new CoreStateLoader(getDataFolder().toPath(), this::getResource);

        Result<CoreState> initial = loader.load();
        if (!initial.isAccepted()) {
            logProblems("Configuration rejected; starting with built-in defaults and every module off",
                    initial.problems());
            initial = loader.loadDefaults();
            if (!initial.isAccepted()) {
                logProblems("Bundled defaults are invalid; VibeCore cannot start", initial.problems());
                getServer().getPluginManager().disablePlugin(this);
                return;
            }
        }
        state = new ActiveState<>(initial.orThrow());

        CoreSettings.WorkerSettings workerSettings = state.get().settings().workers();
        workers = new BoundedWorkers("VibeCore", workerSettings.threads(), workerSettings.queueCapacity(), getLogger());
        tasks = new TaskTracker(this);
        modules = new ModuleRegistry(getLogger());
        messages = new Messages(this::catalog, () -> state.get().settings().defaultLocale());
        modules.register(new MenuModule(this, tasks, workers, messages,
                () -> state.get().settings().allowedCommands()));
        modules.apply(state.get().settings().enabledModules());
        diagnostics = new Diagnostics(getPluginMeta().getVersion(), getServer(), state, modules, workers, tasks);

        PluginCommand command = Objects.requireNonNull(getCommand("vibecore"), "vibecore command missing");
        VibeCoreCommand executor = new VibeCoreCommand(this, messages);
        command.setExecutor(executor);
        command.setTabCompleter(executor);
    }

    @Override
    public void onDisable() {
        if (modules != null) {
            modules.stopAll();
        }
        if (tasks != null) {
            tasks.cancelAll();
        }
        if (workers != null) {
            workers.close();
        }
    }

    /**
     * Validates configuration and messages on a worker, then activates the
     * candidate on the main thread only if the whole candidate was accepted.
     */
    public void reload(CommandSender sender) {
        if (!reloading.compareAndSet(false, true)) {
            messages.send(sender, "reload.in-progress");
            return;
        }
        UUID requester = sender instanceof Player player ? player.getUniqueId() : null;
        messages.send(sender, "reload.started");
        var submitted = workers.trySubmit(loader::load);
        if (submitted.isEmpty()) {
            reloading.set(false);
            messages.send(sender, "command.busy");
            return;
        }
        submitted.get().whenComplete((candidate, failure) -> tasks.runOnMain(() -> {
            try {
                CommandSender target = requester == null ? sender : getServer().getPlayer(requester);
                Result<CoreState> result = failure == null ? candidate
                        : Result.rejected("reload", "failure", "unexpected " + failure.getClass().getSimpleName());
                if (failure != null) {
                    getLogger().log(Level.SEVERE, "Reload failed unexpectedly", failure);
                }
                applyReload(result, target);
            } finally {
                reloading.set(false);
            }
        }));
    }

    private void applyReload(Result<CoreState> candidate, CommandSender target) {
        CoreSettings previous = state.get().settings();
        state.offer(candidate);
        if (!candidate.isAccepted()) {
            logProblems("Reload rejected; previous configuration stays active", candidate.problems());
            if (target != null) {
                List<Problem> problems = candidate.problems();
                messages.send(target, "reload.rejected", "count", Integer.toString(problems.size()));
                problems.stream().limit(PROBLEMS_SHOWN_IN_CHAT).forEach(problem ->
                        messages.sendLine(target, "reload.problem", "problem", problem.toString()));
                if (problems.size() > PROBLEMS_SHOWN_IN_CHAT) {
                    messages.sendLine(target, "reload.more",
                            "count", Integer.toString(problems.size() - PROBLEMS_SHOWN_IN_CHAT));
                }
            }
            return;
        }
        CoreSettings current = state.get().settings();
        List<Problem> moduleProblems = List.of();
        boolean retryFailed = modules.statuses().stream().anyMatch(status ->
                status.state() == ModuleState.FAILED && current.enabledModules().contains(status.id()));
        if (!current.enabledModules().equals(previous.enabledModules()) || retryFailed) {
            modules.apply(current.enabledModules());
        } else {
            moduleProblems = modules.reloadRunning();
            if (!moduleProblems.isEmpty()) {
                logProblems("Module files rejected; previous module state stays active", moduleProblems);
            }
        }
        if (target != null) {
            messages.send(target, "reload.success", "revision", Long.toString(state.revision().revision()));
            if (!moduleProblems.isEmpty()) {
                messages.send(target, "reload.module-rejected", "count", Integer.toString(moduleProblems.size()));
                moduleProblems.stream().limit(PROBLEMS_SHOWN_IN_CHAT).forEach(problem ->
                        messages.sendLine(target, "reload.problem", "problem", problem.toString()));
            }
            if (!current.workers().equals(previous.workers())) {
                messages.send(target, "reload.restart-required");
            }
        }
    }

    private void logProblems(String headline, List<Problem> problems) {
        getLogger().warning(headline + ":");
        for (Problem problem : problems) {
            getLogger().warning("  " + problem);
        }
    }

    public ActiveState<CoreState> state() {
        return state;
    }

    public MessageCatalog catalog() {
        return state.get().messages();
    }

    public ModuleRegistry modules() {
        return modules;
    }

    public Diagnostics diagnostics() {
        return diagnostics;
    }
}
