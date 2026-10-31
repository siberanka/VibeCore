package com.siberanka.vibecore.runtime;

import com.siberanka.vibecore.core.io.AtomicFiles;
import com.siberanka.vibecore.core.task.BoundedWorkers;
import com.siberanka.vibecore.core.task.TaskTracker;
import com.siberanka.vibecore.core.validation.Problem;
import com.siberanka.vibecore.core.validation.Result;
import com.siberanka.vibecore.logic.LogicEngine;
import com.siberanka.vibecore.project.Identifier;
import com.siberanka.vibecore.variable.VariableDefinition;
import com.siberanka.vibecore.variable.VariableFile;
import com.siberanka.vibecore.variable.VariableStore;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.random.RandomGenerator;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * The variables of {@code variables.yml} shared by menus, HUDs and other
 * modules, with their store and logic engine.
 *
 * <p>Persistent values are saved atomically to {@code data/variables.txt} in
 * the background every five minutes and on shutdown. Unreadable stored data
 * stops start-up instead of being overwritten. Online-player values are
 * dropped when the player leaves, after every module has handled the quit.
 */
public final class VariableService implements Listener {

    private static final long SAVE_PERIOD_TICKS = 20L * 60 * 5;
    private static final int MAX_VALUES_PER_PLAYER = 256;

    private final JavaPlugin plugin;
    private final TaskTracker tasks;
    private final BoundedWorkers workers;
    private final Path declarations;
    private final Path dataFile;
    private final Object saveLock = new Object();
    private final AtomicLong saveSequence = new AtomicLong();
    private long lastWrittenSequence;
    private volatile List<VariableDefinition> definitions = List.of();
    private volatile VariableStore store;
    private volatile LogicEngine engine;
    private BukkitTask saveTask;

    public VariableService(JavaPlugin plugin, TaskTracker tasks, BoundedWorkers workers) {
        this.plugin = plugin;
        this.tasks = tasks;
        this.workers = workers;
        this.declarations = plugin.getDataFolder().toPath().resolve(VariableFile.NAME);
        this.dataFile = plugin.getDataFolder().toPath().resolve("data").resolve("variables.txt");
    }

    /** Loads declarations and stored values; rejects start-up rather than overwrite unreadable data. */
    public List<Problem> start() throws IOException {
        Result<List<VariableDefinition>> loaded = VariableFile.load(declarations);
        if (!loaded.isAccepted()) {
            return loaded.problems();
        }
        VariableStore created = VariableStore.create(loaded.orThrow(), MAX_VALUES_PER_PLAYER).orThrow();
        if (Files.isRegularFile(dataFile, LinkOption.NOFOLLOW_LINKS)) {
            Result<Integer> restored = created.loadPersistent(Files.readString(dataFile, StandardCharsets.UTF_8));
            if (!restored.isAccepted()) {
                return restored.problems();
            }
        }
        activate(loaded.orThrow(), created);
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        saveTask = tasks.runTimer(this::saveInBackground, SAVE_PERIOD_TICKS, SAVE_PERIOD_TICKS);
        return List.of();
    }

    private void activate(List<VariableDefinition> declared, VariableStore created) {
        definitions = List.copyOf(declared);
        store = created;
        engine = new LogicEngine(created, RandomGenerator.getDefault());
    }

    public boolean running() {
        return store != null;
    }

    public void stop() {
        HandlerList.unregisterAll(this);
        if (saveTask != null) {
            tasks.cancel(saveTask);
            saveTask = null;
        }
        if (store != null) {
            saveNow();
        }
    }

    /**
     * Re-reads the declarations. When they changed, persistent values move to a
     * new store; values of removed or changed variables are kept as orphans.
     *
     * @return true when the declarations changed, so open screens should close
     */
    public Result<Boolean> reload() {
        Result<List<VariableDefinition>> loaded = VariableFile.load(declarations);
        if (!loaded.isAccepted()) {
            return Result.rejected(loaded.problems());
        }
        if (loaded.orThrow().equals(definitions)) {
            return Result.accepted(false);
        }
        Result<VariableStore> created = VariableStore.create(loaded.orThrow(), MAX_VALUES_PER_PLAYER);
        if (!created.isAccepted()) {
            return Result.rejected(created.problems());
        }
        Result<Integer> moved = created.orThrow().loadPersistent(store.serializePersistent());
        if (!moved.isAccepted()) {
            store.markPersistentDirty();
            return Result.rejected(moved.problems());
        }
        created.orThrow().markPersistentDirty();
        activate(loaded.orThrow(), created.orThrow());
        return Result.accepted(true);
    }

    public VariableStore store() {
        return store;
    }

    public LogicEngine engine() {
        return engine;
    }

    public Map<Identifier, VariableDefinition> definitions() {
        Map<Identifier, VariableDefinition> byKey = new LinkedHashMap<>();
        definitions.forEach(definition -> byKey.put(definition.key(), definition));
        return byKey;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        VariableStore current = store;
        if (current != null) {
            current.disconnect(event.getPlayer().getUniqueId());
        }
    }

    private void saveInBackground() {
        VariableStore owner = store;
        if (!owner.persistentDirty()) {
            return;
        }
        String text = owner.serializePersistent();
        long sequence = saveSequence.incrementAndGet();
        var submitted = workers.trySubmit(() -> {
            write(text, sequence);
            return null;
        });
        if (submitted.isEmpty()) {
            owner.markPersistentDirty();
            return;
        }
        submitted.get().whenComplete((ignored, failure) -> {
            if (failure != null) {
                owner.markPersistentDirty();
                plugin.getLogger().log(Level.SEVERE, "Could not save variables", failure);
            }
        });
    }

    private void saveNow() {
        try {
            write(store.serializePersistent(), saveSequence.incrementAndGet());
        } catch (IOException failure) {
            store.markPersistentDirty();
            plugin.getLogger().log(Level.SEVERE, "Could not save variables", failure);
        }
    }

    private void write(String text, long sequence) throws IOException {
        synchronized (saveLock) {
            if (sequence <= lastWrittenSequence) {
                return;
            }
            AtomicFiles.writeString(dataFile, text);
            lastWrittenSequence = sequence;
        }
    }
}
