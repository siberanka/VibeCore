package com.siberanka.vibecore.hud;

import com.siberanka.vibecore.core.module.ModuleId;
import com.siberanka.vibecore.core.module.VibeModule;
import com.siberanka.vibecore.core.validation.Problem;
import com.siberanka.vibecore.core.validation.Result;
import com.siberanka.vibecore.logic.LogicEngine;
import com.siberanka.vibecore.logic.Operand;
import com.siberanka.vibecore.project.Identifier;
import com.siberanka.vibecore.runtime.ModuleContext;
import com.siberanka.vibecore.variable.VariableContext;
import com.siberanka.vibecore.variable.VariableValue;
import com.siberanka.vibecore.world.PlayerWorld;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;

/**
 * Persistent HUDs on the action bar and boss bar, and queued popups.
 *
 * <p>Each player sees the first HUD whose condition holds, re-evaluated at
 * the HUD's refresh rate, unless an operator chose one manually. Text is only
 * re-sent when it changed or the action bar is about to fade. An action-bar
 * popup takes the action bar while it is shown; title popups do not. Manual
 * choices and queued popups last until the player leaves.
 */
public final class HudModule implements VibeModule, Listener {

    public static final int MAX_QUEUED_POPUPS = 8;
    private static final long TICK_STEP = 2;
    private static final long ACTIONBAR_RESEND_TICKS = 40;
    private static final Identifier HIDDEN = new Identifier("vibecore", "hidden");

    /** Why a player sees what they see. */
    public record Selection(Optional<Identifier> hud, String reason) {
    }

    private static final class Viewer {
        Identifier manual;
        Identifier current;
        Component actionbar;
        long actionbarSentAt = Long.MIN_VALUE;
        BossBar bar;
        long nextRefresh;
        final Deque<PopupDefinition> popups = new ArrayDeque<>();
        PopupDefinition popup;
        long popupEnds;
    }

    private final ModuleContext context;
    private final Map<UUID, Viewer> viewers = new ConcurrentHashMap<>();
    private volatile HudCatalog catalog;
    private BukkitTask task;
    private long tick;

    public HudModule(ModuleContext context) {
        this.context = context;
    }

    @Override
    public ModuleId id() {
        return ModuleId.HUD;
    }

    @Override
    public Optional<String> unsupportedReason() {
        return context.variables().running() ? Optional.empty() : Optional.of("variables are not loaded");
    }

    private Result<HudCatalog> load() {
        return HudCatalog.load(context.plugin().getDataFolder().toPath(), context.variables().definitions(),
                key -> {
                    Material material = Material.matchMaterial(key);
                    return material != null && material.isItem();
                });
    }

    @Override
    public void start() {
        Result<HudCatalog> loaded = load();
        if (!loaded.isAccepted()) {
            loaded.problems().forEach(problem -> context.plugin().getLogger().warning("  " + problem));
            throw new IllegalStateException("HUD files are invalid");
        }
        catalog = loaded.orThrow();
        context.plugin().getServer().getPluginManager().registerEvents(this, context.plugin());
        task = context.tasks().runTimer(this::step, TICK_STEP, TICK_STEP);
    }

    @Override
    public void stop() {
        if (task != null) {
            context.tasks().cancel(task);
            task = null;
        }
        HandlerList.unregisterAll(this);
        for (Map.Entry<UUID, Viewer> entry : viewers.entrySet()) {
            Player player = context.plugin().getServer().getPlayer(entry.getKey());
            if (player != null) {
                clear(player, entry.getValue());
            }
        }
        viewers.clear();
    }

    @Override
    public List<Problem> reload() {
        Result<HudCatalog> loaded = load();
        if (!loaded.isAccepted()) {
            return loaded.problems();
        }
        catalog = loaded.orThrow();
        viewers.values().forEach(viewer -> viewer.nextRefresh = 0);
        return List.of();
    }

    public HudCatalog catalog() {
        return catalog;
    }

    private Viewer viewer(Player player) {
        return viewers.computeIfAbsent(player.getUniqueId(), id -> new Viewer());
    }

    /** Shows a HUD regardless of conditions until reset or the player leaves. */
    public boolean show(Player player, Identifier hud) {
        if (catalog.hud(hud).isEmpty()) {
            return false;
        }
        viewer(player).manual = hud;
        viewer(player).nextRefresh = 0;
        return true;
    }

    public void hide(Player player) {
        viewer(player).manual = HIDDEN;
        viewer(player).nextRefresh = 0;
    }

    public void reset(Player player) {
        viewer(player).manual = null;
        viewer(player).nextRefresh = 0;
    }

    public Selection selection(Player player) {
        Viewer viewer = viewer(player);
        if (HIDDEN.equals(viewer.manual)) {
            return new Selection(Optional.empty(), "hidden manually");
        }
        if (viewer.manual != null) {
            return new Selection(Optional.of(viewer.manual), "chosen manually");
        }
        return select(player).map(hud -> new Selection(Optional.of(hud.id()), "first matching condition"))
                .orElseGet(() -> new Selection(Optional.empty(), "no HUD condition matches"));
    }

    /** Queues a popup; returns false when the player's queue is full. */
    public boolean popup(Player player, PopupDefinition popup) {
        Viewer viewer = viewer(player);
        synchronized (viewer.popups) {
            if (viewer.popups.size() >= MAX_QUEUED_POPUPS) {
                return false;
            }
            viewer.popups.addLast(popup);
        }
        return true;
    }

    private Optional<HudDefinition> select(Player player) {
        PlayerWorld world = context.world(player);
        VariableContext variables = VariableContext.player(player.getUniqueId());
        for (HudDefinition hud : catalog.huds()) {
            if (hud.when() == null) {
                return Optional.of(hud);
            }
            Result<Boolean> matches = context.variables().engine().test(hud.when(), variables,
                    LogicEngine.Limits.DEFAULT, world);
            if (matches.isAccepted() && matches.orThrow()) {
                return Optional.of(hud);
            }
        }
        return Optional.empty();
    }

    private void step() {
        tick += TICK_STEP;
        for (Player player : context.plugin().getServer().getOnlinePlayers()) {
            Viewer viewer = viewer(player);
            stepPopup(player, viewer);
            boolean popupOnActionbar = viewer.popup != null
                    && viewer.popup.surface() == PopupDefinition.Surface.ACTIONBAR;
            if (tick >= viewer.nextRefresh || (!popupOnActionbar && needsActionbarResend(viewer))) {
                refresh(player, viewer);
            }
        }
    }

    private boolean needsActionbarResend(Viewer viewer) {
        return viewer.actionbar != null && tick - viewer.actionbarSentAt >= ACTIONBAR_RESEND_TICKS;
    }

    private void stepPopup(Player player, Viewer viewer) {
        if (viewer.popup != null && tick < viewer.popupEnds) {
            return;
        }
        viewer.popup = null;
        PopupDefinition next;
        synchronized (viewer.popups) {
            next = viewer.popups.pollFirst();
        }
        if (next == null) {
            return;
        }
        viewer.popup = next;
        viewer.popupEnds = tick + Math.max(TICK_STEP, next.millis() / 50);
        PlayerWorld world = context.world(player);
        Component text = context.text().render(next.text(), player, world, null);
        if (next.surface() == PopupDefinition.Surface.TITLE) {
            Component subtitle = next.subtitle() == null ? Component.empty()
                    : context.text().render(next.subtitle(), player, world, null);
            player.showTitle(Title.title(text, subtitle, Title.Times.times(Duration.ofMillis(150),
                    Duration.ofMillis(next.millis()), Duration.ofMillis(250))));
        } else {
            player.sendActionBar(text);
            viewer.actionbarSentAt = tick;
        }
    }

    private void refresh(Player player, Viewer viewer) {
        Optional<HudDefinition> hud;
        if (HIDDEN.equals(viewer.manual)) {
            hud = Optional.empty();
        } else if (viewer.manual != null) {
            hud = catalog.hud(viewer.manual);
        } else {
            hud = select(player);
        }
        viewer.current = hud.map(HudDefinition::id).orElse(null);
        viewer.nextRefresh = tick + hud.map(HudDefinition::refreshTicks).orElse(20);
        PlayerWorld world = context.world(player);
        boolean popupOnActionbar = viewer.popup != null && viewer.popup.surface() == PopupDefinition.Surface.ACTIONBAR;
        Component actionbar = hud.map(HudDefinition::actionbar).filter(Objects::nonNull)
                .map(text -> context.text().render(text, player, world, null)).orElse(null);
        if (!popupOnActionbar && actionbar != null
                && (!actionbar.equals(viewer.actionbar) || needsActionbarResend(viewer))) {
            player.sendActionBar(actionbar);
            viewer.actionbarSentAt = tick;
        }
        viewer.actionbar = actionbar;
        updateBar(player, viewer, hud.map(HudDefinition::bossbar).orElse(null), world);
    }

    private void updateBar(Player player, Viewer viewer, HudDefinition.Bar definition, PlayerWorld world) {
        if (definition == null) {
            if (viewer.bar != null) {
                player.hideBossBar(viewer.bar);
                viewer.bar = null;
            }
            return;
        }
        Component name = context.text().render(definition.text(), player, world, null);
        float progress = progress(player, definition, world);
        BossBar.Color color = BossBar.Color.valueOf(definition.color().toUpperCase(Locale.ROOT));
        BossBar.Overlay overlay = BossBar.Overlay.valueOf(definition.overlay().toUpperCase(Locale.ROOT));
        if (viewer.bar == null) {
            viewer.bar = BossBar.bossBar(name, progress, color, overlay);
            player.showBossBar(viewer.bar);
            return;
        }
        if (!viewer.bar.name().equals(name)) {
            viewer.bar.name(name);
        }
        if (viewer.bar.progress() != progress) {
            viewer.bar.progress(progress);
        }
        if (viewer.bar.color() != color) {
            viewer.bar.color(color);
        }
        if (viewer.bar.overlay() != overlay) {
            viewer.bar.overlay(overlay);
        }
    }

    private float progress(Player player, HudDefinition.Bar bar, PlayerWorld world) {
        double value = number(player, bar.value(), world);
        double max = number(player, bar.max(), world);
        if (!(max > 0) || !Double.isFinite(value)) {
            return 0f;
        }
        return (float) Math.max(0, Math.min(1, value / max));
    }

    private double number(Player player, Operand operand, PlayerWorld world) {
        Result<VariableValue> value = context.variables().engine().value(operand,
                VariableContext.player(player.getUniqueId()), world);
        return value.isAccepted() && value.orThrow() instanceof VariableValue.NumberValue number ? number.value() : 0;
    }

    private void clear(Player player, Viewer viewer) {
        if (viewer.bar != null) {
            player.hideBossBar(viewer.bar);
        }
        if (viewer.popup != null && viewer.popup.surface() == PopupDefinition.Surface.TITLE) {
            player.clearTitle();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Viewer viewer = viewers.remove(event.getPlayer().getUniqueId());
        if (viewer != null) {
            clear(event.getPlayer(), viewer);
        }
    }
}
