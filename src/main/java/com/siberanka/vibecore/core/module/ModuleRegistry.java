package com.siberanka.vibecore.core.module;

import com.siberanka.vibecore.core.validation.Problem;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Starts and stops modules in isolation. A failure or a missing capability in
 * one module is recorded against that module only.
 */
public final class ModuleRegistry {

    private final Logger logger;
    private final Map<ModuleId, VibeModule> implementations = new EnumMap<>(ModuleId.class);
    private final Map<ModuleId, ModuleStatus> statuses = new EnumMap<>(ModuleId.class);
    private final List<ModuleId> startOrder = new ArrayList<>();

    public ModuleRegistry(Logger logger) {
        this.logger = logger;
    }

    public void register(VibeModule module) {
        if (implementations.putIfAbsent(module.id(), module) != null) {
            throw new IllegalStateException("Module registered twice: " + module.id());
        }
    }

    /** Applies the requested module set, stopping running modules first. */
    public synchronized void apply(Set<ModuleId> requested) {
        stopAll();
        for (ModuleId id : ModuleId.values()) {
            VibeModule module = implementations.get(id);
            boolean wanted = requested.contains(id);
            if (module == null) {
                statuses.put(id, new ModuleStatus(id,
                        wanted ? ModuleState.NOT_IMPLEMENTED : ModuleState.DISABLED, false,
                        wanted ? "not available in this build" : "disabled in configuration"));
                continue;
            }
            if (!wanted) {
                statuses.put(id, new ModuleStatus(id, ModuleState.DISABLED, module.experimental(),
                        "disabled in configuration"));
                continue;
            }
            start(module);
        }
    }

    private void start(VibeModule module) {
        ModuleId id = module.id();
        try {
            var unsupported = module.unsupportedReason();
            if (unsupported.isPresent()) {
                statuses.put(id, new ModuleStatus(id, ModuleState.UNSUPPORTED, module.experimental(),
                        unsupported.get()));
                return;
            }
            module.start();
            startOrder.add(id);
            statuses.put(id, new ModuleStatus(id, ModuleState.ENABLED, module.experimental(), "running"));
        } catch (Exception | LinkageError failure) {
            logger.log(Level.SEVERE, "Module " + id.key() + " failed to start", failure);
            statuses.put(id, new ModuleStatus(id, ModuleState.FAILED, module.experimental(),
                    "start-up failed: " + failure.getClass().getSimpleName()));
            safeStop(module);
        }
    }

    /** Reloads every running module and collects the problems of rejected candidates. */
    public synchronized List<Problem> reloadRunning() {
        List<Problem> problems = new ArrayList<>();
        for (ModuleId id : startOrder) {
            try {
                problems.addAll(implementations.get(id).reload());
            } catch (RuntimeException | LinkageError failure) {
                logger.log(Level.SEVERE, "Module " + id.key() + " failed to reload", failure);
                problems.add(new Problem(id.key(), "failure",
                        "reload failed: " + failure.getClass().getSimpleName()));
            }
        }
        return problems;
    }

    /** Returns the running implementation of a module, if it is enabled. */
    public synchronized <T extends VibeModule> Optional<T> running(ModuleId id, Class<T> type) {
        VibeModule module = implementations.get(id);
        return startOrder.contains(id) && type.isInstance(module)
                ? Optional.of(type.cast(module)) : Optional.empty();
    }

    /** Stops running modules in reverse start order. */
    public synchronized void stopAll() {
        List<ModuleId> reverse = new ArrayList<>(startOrder);
        Collections.reverse(reverse);
        for (ModuleId id : reverse) {
            safeStop(implementations.get(id));
            ModuleStatus previous = statuses.get(id);
            statuses.put(id, new ModuleStatus(id, ModuleState.DISABLED,
                    previous != null && previous.experimental(), "stopped"));
        }
        startOrder.clear();
    }

    private void safeStop(VibeModule module) {
        try {
            module.stop();
        } catch (Exception | LinkageError failure) {
            logger.log(Level.WARNING, "Module " + module.id().key() + " did not stop cleanly", failure);
        }
    }

    public synchronized Collection<ModuleStatus> statuses() {
        List<ModuleStatus> snapshot = new ArrayList<>();
        for (ModuleId id : ModuleId.values()) {
            ModuleStatus status = statuses.get(id);
            snapshot.add(status != null ? status
                    : new ModuleStatus(id, ModuleState.DISABLED, false, "not configured"));
        }
        return List.copyOf(snapshot);
    }

    public synchronized ModuleStatus status(ModuleId id) {
        ModuleStatus status = statuses.get(id);
        return status != null ? status : new ModuleStatus(id, ModuleState.DISABLED, false, "not configured");
    }
}
