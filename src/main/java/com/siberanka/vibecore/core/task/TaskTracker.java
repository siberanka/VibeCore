package com.siberanka.vibecore.core.task;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * Owns every scheduler task created through it, so disabling the plugin or a
 * module cancels all of them and leaves nothing running behind.
 */
public final class TaskTracker {

    private final Plugin plugin;
    private final Set<BukkitTask> tasks = Collections.newSetFromMap(new ConcurrentHashMap<>());

    public TaskTracker(Plugin plugin) {
        this.plugin = plugin;
    }

    public BukkitTask runLater(Runnable work, long delayTicks) {
        BukkitTask[] holder = new BukkitTask[1];
        BukkitTask task = plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            try {
                work.run();
            } finally {
                tasks.remove(holder[0]);
            }
        }, delayTicks);
        holder[0] = task;
        track(task);
        return task;
    }

    public BukkitTask runTimer(Runnable work, long delayTicks, long periodTicks) {
        if (periodTicks < 1) {
            throw new IllegalArgumentException("periodTicks must be at least 1");
        }
        return track(plugin.getServer().getScheduler().runTaskTimer(plugin, work, delayTicks, periodTicks));
    }

    /** Runs work on the main thread, for results produced by background workers. */
    public void runOnMain(Runnable work) {
        if (plugin.isEnabled()) {
            runLater(work, 0);
        }
    }

    private BukkitTask track(BukkitTask task) {
        if (!task.isCancelled()) {
            tasks.add(task);
        }
        return task;
    }

    public void cancel(BukkitTask task) {
        task.cancel();
        tasks.remove(task);
    }

    public int active() {
        tasks.removeIf(BukkitTask::isCancelled);
        return tasks.size();
    }

    public void cancelAll() {
        for (BukkitTask task : tasks) {
            task.cancel();
        }
        tasks.clear();
    }
}
