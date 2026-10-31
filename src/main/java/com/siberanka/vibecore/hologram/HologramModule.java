package com.siberanka.vibecore.hologram;

import com.siberanka.vibecore.core.io.AtomicFiles;
import com.siberanka.vibecore.core.module.ModuleId;
import com.siberanka.vibecore.core.module.VibeModule;
import com.siberanka.vibecore.core.validation.Problem;
import com.siberanka.vibecore.core.validation.Result;
import com.siberanka.vibecore.logic.LogicEngine;
import com.siberanka.vibecore.project.Identifier;
import com.siberanka.vibecore.runtime.ModuleContext;
import com.siberanka.vibecore.variable.VariableContext;
import com.siberanka.vibecore.world.PlayerWorld;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.joml.Vector3f;

/**
 * Text holograms. Every viewer gets their own text display, hidden from all
 * other players, so each one sees their own placeholders and only the
 * holograms whose condition they meet. Display entities are never saved with
 * the world: they are created only near viewers in loaded chunks and removed
 * when the viewer leaves the range, quits, or the module stops.
 */
public final class HologramModule implements VibeModule, Listener {

    private static final long STEP_TICKS = 10;

    private final ModuleContext context;
    private final Path placementsFile;
    private final Map<String, Map<UUID, Shown>> shown = new HashMap<>();
    private volatile HologramCatalog catalog;
    private HologramPlacements placements;
    private BukkitTask task;

    /** A display shown to one viewer and the text it currently has. */
    private record Shown(TextDisplay entity, Component text) {
    }

    public HologramModule(ModuleContext context) {
        this.context = context;
        this.placementsFile = context.plugin().getDataFolder().toPath().resolve("data").resolve("holograms.txt");
    }

    @Override
    public ModuleId id() {
        return ModuleId.HOLOGRAM;
    }

    @Override
    public Optional<String> unsupportedReason() {
        return context.variables().running() ? Optional.empty() : Optional.of("variables are not loaded");
    }

    @Override
    public void start() throws IOException {
        Result<HologramCatalog> loaded = HologramCatalog.load(context.plugin().getDataFolder().toPath(),
                context.variables().definitions());
        if (!loaded.isAccepted()) {
            loaded.problems().forEach(problem -> context.plugin().getLogger().warning("  " + problem));
            throw new IllegalStateException("hologram files are invalid");
        }
        HologramPlacements stored = new HologramPlacements();
        if (Files.isRegularFile(placementsFile, LinkOption.NOFOLLOW_LINKS)) {
            Result<HologramPlacements> read = HologramPlacements.deserialize(
                    Files.readString(placementsFile, StandardCharsets.UTF_8));
            if (!read.isAccepted()) {
                read.problems().forEach(problem -> context.plugin().getLogger().warning("  " + problem));
                throw new IllegalStateException("data/holograms.txt is unreadable; refusing to overwrite it");
            }
            stored = read.orThrow();
        }
        catalog = loaded.orThrow();
        placements = stored;
        context.plugin().getServer().getPluginManager().registerEvents(this, context.plugin());
        task = context.tasks().runTimer(this::step, STEP_TICKS, STEP_TICKS);
    }

    @Override
    public void stop() {
        if (task != null) {
            context.tasks().cancel(task);
            task = null;
        }
        HandlerList.unregisterAll(this);
        shown.values().forEach(viewers -> viewers.values().forEach(display -> display.entity().remove()));
        shown.clear();
    }

    @Override
    public List<Problem> reload() {
        Result<HologramCatalog> loaded = HologramCatalog.load(context.plugin().getDataFolder().toPath(),
                context.variables().definitions());
        if (!loaded.isAccepted()) {
            return loaded.problems();
        }
        catalog = loaded.orThrow();
        shown.values().forEach(viewers -> viewers.values().forEach(display -> display.entity().remove()));
        shown.clear();
        return List.of();
    }

    public HologramCatalog catalog() {
        return catalog;
    }

    public HologramPlacements placements() {
        return placements;
    }

    /** Places a design at a location and saves the placements. */
    public Result<HologramPlacements.Placement> place(Identifier design, Location location) {
        if (catalog.design(design).isEmpty()) {
            return Result.rejected("design", "unknown-design", "no such design");
        }
        Result<HologramPlacements.Placement> added = placements.add(design, location.getWorld().getUID(),
                location.getX(), location.getY(), location.getZ());
        if (added.isAccepted()) {
            save();
        }
        return added;
    }

    public boolean remove(String id) {
        boolean removed = placements.remove(id);
        if (removed) {
            despawn(id.toLowerCase(Locale.ROOT));
            save();
        }
        return removed;
    }

    public Optional<HologramPlacements.Placement> move(String id, Location location) {
        Optional<HologramPlacements.Placement> moved = placements.move(id, location.getWorld().getUID(),
                location.getX(), location.getY(), location.getZ());
        moved.ifPresent(placement -> {
            despawn(placement.id());
            save();
        });
        return moved;
    }

    private void save() {
        String text = placements.serialize();
        var submitted = context.workers().trySubmit(() -> {
            AtomicFiles.writeString(placementsFile, text);
            return null;
        });
        if (submitted.isEmpty()) {
            try {
                AtomicFiles.writeString(placementsFile, text);
            } catch (IOException failure) {
                context.plugin().getLogger().severe("Could not save holograms: " + failure.getMessage());
            }
        }
    }

    private void despawn(String placement) {
        Map<UUID, Shown> viewers = shown.remove(placement);
        if (viewers != null) {
            viewers.values().forEach(display -> display.entity().remove());
        }
    }

    private void step() {
        long now = System.currentTimeMillis();
        List<Player> online = new ArrayList<>(context.plugin().getServer().getOnlinePlayers());
        for (HologramPlacements.Placement placement : placements.all()) {
            HologramDesign design = catalog.design(placement.design()).orElse(null);
            World world = context.plugin().getServer().getWorld(placement.world());
            if (design == null || world == null
                    || !world.isChunkLoaded((int) Math.floor(placement.x()) >> 4, (int) Math.floor(placement.z()) >> 4)) {
                despawn(placement.id());
                continue;
            }
            Map<UUID, Shown> viewers = shown.computeIfAbsent(placement.id(), id -> new HashMap<>());
            double range = design.viewRange() * design.viewRange();
            for (Player player : online) {
                Location at = player.getLocation();
                boolean inRange = player.getWorld().equals(world)
                        && placement.distanceSquared(at.getX(), at.getY(), at.getZ()) <= range;
                PlayerWorld facts = context.world(player);
                boolean visible = inRange && meets(design, player, facts);
                Shown current = viewers.get(player.getUniqueId());
                if (!visible) {
                    if (current != null) {
                        current.entity().remove();
                        viewers.remove(player.getUniqueId());
                    }
                    continue;
                }
                Component text = Component.join(JoinConfiguration.newlines(), design.slideAt(now).lines().stream()
                        .map(line -> context.text().render(line, player, facts, null)).toList());
                if (current == null || !current.entity().isValid()) {
                    viewers.put(player.getUniqueId(), new Shown(spawn(world, placement, design, player, text), text));
                } else if (!current.text().equals(text)) {
                    current.entity().text(text);
                    viewers.put(player.getUniqueId(), new Shown(current.entity(), text));
                }
            }
            Iterator<Map.Entry<UUID, Shown>> stale = viewers.entrySet().iterator();
            while (stale.hasNext()) {
                Map.Entry<UUID, Shown> entry = stale.next();
                if (context.plugin().getServer().getPlayer(entry.getKey()) == null) {
                    entry.getValue().entity().remove();
                    stale.remove();
                }
            }
        }
        for (String id : List.copyOf(shown.keySet())) {
            if (placements.get(id).isEmpty()) {
                despawn(id);
            }
        }
    }

    private boolean meets(HologramDesign design, Player player, PlayerWorld facts) {
        if (design.visible() == null) {
            return true;
        }
        Result<Boolean> result = context.variables().engine().test(design.visible(),
                VariableContext.player(player.getUniqueId()), LogicEngine.Limits.DEFAULT, facts);
        return result.isAccepted() && result.orThrow();
    }

    private TextDisplay spawn(World world, HologramPlacements.Placement placement, HologramDesign design,
                              Player viewer, Component text) {
        Location location = new Location(world, placement.x(), placement.y(), placement.z());
        TextDisplay display = world.spawn(location, TextDisplay.class, entity -> {
            entity.setPersistent(false);
            entity.setVisibleByDefault(false);
            entity.text(text);
            entity.setBillboard(Display.Billboard.valueOf(design.billboard().name()));
            entity.setShadowed(design.shadow());
            if (design.background() != null) {
                entity.setDefaultBackground(false);
                entity.setBackgroundColor(Color.fromARGB(design.background()));
            }
            entity.setTransformation(new Transformation(new Vector3f(), entity.getTransformation().getLeftRotation(),
                    new Vector3f(design.scale(), design.scale(), design.scale()),
                    entity.getTransformation().getRightRotation()));
            entity.setViewRange((float) (design.viewRange() / 64.0));
        });
        viewer.showEntity(context.plugin(), display);
        return display;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        UUID viewer = event.getPlayer().getUniqueId();
        for (Map<UUID, Shown> viewers : shown.values()) {
            Shown display = viewers.remove(viewer);
            if (display != null) {
                display.entity().remove();
            }
        }
    }
}
