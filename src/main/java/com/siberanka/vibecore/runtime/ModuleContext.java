package com.siberanka.vibecore.runtime;

import com.siberanka.vibecore.core.task.BoundedWorkers;
import com.siberanka.vibecore.core.task.TaskTracker;
import com.siberanka.vibecore.core.text.Messages;
import com.siberanka.vibecore.pack.GlyphIndex;
import com.siberanka.vibecore.world.PlayerWorld;
import java.nio.file.Path;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/** Services shared by the display modules. */
public record ModuleContext(JavaPlugin plugin, TaskTracker tasks, BoundedWorkers workers, Messages messages,
                            VariableService variables, ViewerText text, GlyphIndex glyphs) {

    /** The viewer's money, items, permissions and built-in data for logic chains. */
    public PlayerWorld world(Player viewer) {
        Path ledger = plugin.getDataFolder().toPath().resolve("data").resolve("transactions.log");
        return new PlayerWorld(viewer, messages, plugin.getLogger(), ledger);
    }
}
