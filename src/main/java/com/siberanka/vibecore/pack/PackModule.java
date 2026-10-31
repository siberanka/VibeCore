package com.siberanka.vibecore.pack;

import com.siberanka.vibecore.core.io.AtomicFiles;
import com.siberanka.vibecore.core.module.ModuleId;
import com.siberanka.vibecore.core.module.VibeModule;
import com.siberanka.vibecore.core.task.BoundedWorkers;
import com.siberanka.vibecore.core.task.TaskTracker;
import com.siberanka.vibecore.core.validation.Problem;
import com.siberanka.vibecore.core.validation.Result;
import com.siberanka.vibecore.pack.registry.StableIdRegistry;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.logging.Level;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Builds the Java resource pack from {@code assets/} on a background worker.
 * A failed build leaves the previous pack file, hash and glyph assignments in
 * place; glyph code points are kept in {@code data/glyphs.txt} so they never
 * move between builds.
 */
public final class PackModule implements VibeModule {

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

    public PackModule(JavaPlugin plugin, TaskTracker tasks, BoundedWorkers workers, GlyphIndex glyphs) {
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
        build(result -> {
            if (!result.isAccepted()) {
                plugin.getLogger().warning("Resource pack not built:");
                result.problems().forEach(problem -> plugin.getLogger().warning("  " + problem));
            }
        });
    }

    @Override
    public void stop() {
        glyphs.update(Map.of());
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
