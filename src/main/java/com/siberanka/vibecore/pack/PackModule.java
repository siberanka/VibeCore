package com.siberanka.vibecore.pack;

import com.siberanka.vibecore.core.config.CoreSettings;
import com.siberanka.vibecore.core.io.AtomicFiles;
import com.siberanka.vibecore.core.module.ModuleId;
import com.siberanka.vibecore.core.module.VibeModule;
import com.siberanka.vibecore.core.task.BoundedWorkers;
import com.siberanka.vibecore.core.task.TaskTracker;
import com.siberanka.vibecore.core.text.Messages;
import com.siberanka.vibecore.core.validation.Problem;
import com.siberanka.vibecore.core.validation.Result;
import com.siberanka.vibecore.pack.registry.StableIdRegistry;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Level;
import net.kyori.adventure.resource.ResourcePackInfo;
import net.kyori.adventure.resource.ResourcePackRequest;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Builds the Java resource pack from {@code assets/} on a background worker.
 * A failed build leaves the previous pack file, hash and glyph assignments in
 * place; glyph code points are kept in {@code data/glyphs.txt} so they never
 * move between builds.
 */
public final class PackModule implements VibeModule, Listener {

    /** Result of the last successful build. */
    public record Build(String sha1, int bytes, int glyphs, int items, Instant builtAt) {
    }

    private final JavaPlugin plugin;
    private final TaskTracker tasks;
    private final BoundedWorkers workers;
    private final GlyphIndex glyphs;
    private final Path registryFile;
    private final Path packFile;
    private final AtomicBoolean building = new AtomicBoolean();
    private volatile StableIdRegistry registry;
    private volatile Build lastBuild;
    private volatile List<Problem> lastProblems = List.of();

    private final Messages messages;
    private final Supplier<CoreSettings.PackDelivery> delivery;
    /** Final client answers meaning the pack is not in use. */
    private static final Set<PlayerResourcePackStatusEvent.Status> NOT_LOADED = EnumSet.of(
            PlayerResourcePackStatusEvent.Status.DECLINED, PlayerResourcePackStatusEvent.Status.FAILED_DOWNLOAD,
            PlayerResourcePackStatusEvent.Status.INVALID_URL, PlayerResourcePackStatusEvent.Status.FAILED_RELOAD,
            PlayerResourcePackStatusEvent.Status.DISCARDED);
    private final Map<UUID, PlayerResourcePackStatusEvent.Status> statuses = new ConcurrentHashMap<>();

    public PackModule(JavaPlugin plugin, TaskTracker tasks, BoundedWorkers workers, GlyphIndex glyphs,
                      Messages messages, Supplier<CoreSettings.PackDelivery> delivery) {
        this.messages = messages;
        this.delivery = delivery;
        this.plugin = plugin;
        this.tasks = tasks;
        this.workers = workers;
        this.glyphs = glyphs;
        Path data = plugin.getDataFolder().toPath();
        this.registryFile = data.resolve("data").resolve("glyphs.txt");
        this.packFile = data.resolve("packs").resolve("vibecore-java.zip");
    }

    @Override
    public ModuleId id() {
        return ModuleId.PACK;
    }

    @Override
    public void start() throws IOException {
        if (Files.isRegularFile(registryFile, LinkOption.NOFOLLOW_LINKS)) {
            Result<StableIdRegistry> loaded = StableIdRegistry.deserialize(
                    Files.readString(registryFile, StandardCharsets.UTF_8));
            if (!loaded.isAccepted()) {
                loaded.problems().forEach(problem -> plugin.getLogger().warning("  " + problem));
                throw new IllegalStateException("data/glyphs.txt is unreadable; refusing to reassign glyphs");
            }
            registry = loaded.orThrow();
        } else {
            registry = StableIdRegistry.glyphs("glyph:vibecore", 0xE000, 0xF8FF);
        }
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        build(result -> {
            if (!result.isAccepted()) {
                plugin.getLogger().warning("Resource pack not built:");
                result.problems().forEach(problem -> plugin.getLogger().warning("  " + problem));
            }
        });
    }

    @Override
    public void stop() {
        HandlerList.unregisterAll(this);
        statuses.clear();
        glyphs.update(Map.of());
    }

    /** Offers the last built pack to a player; the reason is returned when it cannot be offered. */
    public Result<String> offer(Player player) {
        Build build = lastBuild;
        CoreSettings.PackDelivery settings = delivery.get();
        if (build == null) {
            return Result.rejected("pack", "no-build", "no pack has been built");
        }
        if (settings.url().isEmpty()) {
            return Result.rejected("pack", "no-url", "pack.url is not set");
        }
        ResourcePackInfo info = ResourcePackInfo.resourcePackInfo(PackDelivery.packId(build.sha1()),
                URI.create(settings.url()), build.sha1());
        player.sendResourcePacks(ResourcePackRequest.resourcePackRequest().packs(info).replace(false)
                .required(settings.required()).prompt(messages.render(messages.localeOf(player), "pack.prompt")));
        statuses.remove(player.getUniqueId());
        return Result.accepted(build.sha1());
    }

    /** Number of online players per last reported pack status. */
    public Map<String, Integer> deliveryCounts() {
        Map<String, Integer> counts = new TreeMap<>();
        statuses.values().forEach(status -> counts.merge(status.name().toLowerCase(Locale.ROOT), 1, Integer::sum));
        return counts;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        if (delivery.get().sendOnJoin() && lastBuild != null && !delivery.get().url().isEmpty()) {
            Player player = event.getPlayer();
            tasks.runLater(() -> {
                if (player.isOnline()) {
                    offer(player);
                }
            }, 20);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onStatus(PlayerResourcePackStatusEvent event) {
        Build build = lastBuild;
        if (build == null || !PackDelivery.packId(build.sha1()).equals(event.getID())) {
            return;
        }
        Player player = event.getPlayer();
        statuses.put(player.getUniqueId(), event.getStatus());
        if (NOT_LOADED.contains(event.getStatus())) {
            if (delivery.get().kickOnDecline()) {
                player.kick(messages.render(messages.localeOf(player), "pack.kick"));
            } else {
                messages.send(player, "pack.not-loaded");
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        statuses.remove(event.getPlayer().getUniqueId());
    }

    @Override
    public List<Problem> reload() {
        Result<PackSource> source = PackSource.load(plugin.getDataFolder().toPath());
        if (!source.isAccepted()) {
            return source.problems();
        }
        build(result -> {
        });
        return List.of();
    }

    public Optional<Build> lastBuild() {
        return Optional.ofNullable(lastBuild);
    }

    public List<Problem> lastProblems() {
        return lastProblems;
    }

    public boolean building() {
        return building.get();
    }

    public Path packFile() {
        return packFile;
    }

    /**
     * Builds the pack in the background and reports on the server thread.
     *
     * @return false when a build is already running or the workers are full
     */
    public boolean build(Consumer<Result<Build>> done) {
        if (!building.compareAndSet(false, true)) {
            return false;
        }
        StableIdRegistry working = StableIdRegistry.deserialize(registry.serialize()).orThrow();
        var submitted = workers.trySubmit(() -> compileAndWrite(working));
        if (submitted.isEmpty()) {
            building.set(false);
            return false;
        }
        submitted.get().whenComplete((outcome, failure) -> tasks.runOnMain(() -> {
            building.set(false);
            Result<Build> result;
            if (failure != null) {
                plugin.getLogger().log(Level.SEVERE, "Resource pack build failed", failure);
                result = Result.rejected("pack", "failure", "unexpected " + failure.getClass().getSimpleName());
            } else {
                result = outcome.result();
            }
            if (result.isAccepted()) {
                registry = working;
                lastBuild = result.orThrow();
                glyphs.update(outcome.glyphs());
                lastProblems = List.of();
            } else {
                lastProblems = result.problems();
            }
            done.accept(result);
        }));
        return true;
    }

    private record Compiled(Result<Build> result, Map<String, Integer> glyphs) {
    }

    private Compiled compileAndWrite(StableIdRegistry working) throws IOException {
        Result<PackSource> source = PackSource.load(plugin.getDataFolder().toPath());
        if (!source.isAccepted()) {
            return new Compiled(Result.rejected(source.problems()), Map.of());
        }
        Result<PackCompiler.Output> output = PackCompiler.compile(source.orThrow(), working);
        if (!output.isAccepted()) {
            return new Compiled(Result.rejected(output.problems()), Map.of());
        }
        PackCompiler.Output pack = output.orThrow();
        AtomicFiles.writeString(registryFile, working.serialize());
        AtomicFiles.write(packFile, pack.zip());
        AtomicFiles.writeString(packFile.resolveSibling("vibecore-java.sha1"), pack.sha1() + "\n");
        Build build = new Build(pack.sha1(), pack.zip().length, pack.glyphs().size(),
                source.orThrow().items().size(), Instant.now());
        return new Compiled(Result.accepted(build), pack.glyphs());
    }
}
