package com.siberanka.vibecore.menu;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;
import com.siberanka.vibecore.core.module.ModuleId;
import com.siberanka.vibecore.core.module.VibeModule;
import com.siberanka.vibecore.core.task.TaskTracker;
import com.siberanka.vibecore.core.text.Messages;
import com.siberanka.vibecore.core.validation.Problem;
import com.siberanka.vibecore.core.validation.Result;
import com.siberanka.vibecore.logic.Action;
import com.siberanka.vibecore.logic.Condition;
import com.siberanka.vibecore.logic.Facts;
import com.siberanka.vibecore.logic.LogicEngine;
import com.siberanka.vibecore.logic.Outcome;
import com.siberanka.vibecore.logic.SideEffects;
import com.siberanka.vibecore.project.Identifier;
import com.siberanka.vibecore.runtime.ModuleContext;
import com.siberanka.vibecore.variable.VariableContext;
import com.siberanka.vibecore.variable.VariableStore;
import com.siberanka.vibecore.variable.VariableValue;
import com.siberanka.vibecore.world.PlayerWorld;
import com.siberanka.vibecore.world.TransactionPlan;
import io.papermc.paper.event.player.AsyncChatEvent;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * Inventory menus. Every interaction with an open menu is cancelled; only a
 * permitted click on a visible item runs that item's action chain, which
 * re-checks its own conditions and commits its variable changes all or none.
 */
public final class MenuModule implements VibeModule, Listener {

    private static final long DEBOUNCE_NANOS = TimeUnit.MILLISECONDS.toNanos(150);

    private final ModuleContext context;
    private final JavaPlugin plugin;
    private final TaskTracker tasks;
    private final Messages messages;
    private final Supplier<Set<String>> allowedCommands;
    private final Map<UUID, MenuSession> sessions = new ConcurrentHashMap<>();
    private final Map<UUID, PendingInput> inputs = new ConcurrentHashMap<>();

    private volatile MenuCatalog catalog;

    public MenuModule(ModuleContext context, Supplier<Set<String>> allowedCommands) {
        this.context = context;
        this.plugin = context.plugin();
        this.tasks = context.tasks();
        this.messages = context.messages();
        this.allowedCommands = allowedCommands;
    }

    private VariableStore store() {
        return context.variables().store();
    }

    private LogicEngine engine() {
        return context.variables().engine();
    }

    @Override
    public ModuleId id() {
        return ModuleId.MENU;
    }

    @Override
    public Optional<String> unsupportedReason() {
        return context.variables().running() ? Optional.empty() : Optional.of("variables are not loaded");
    }

    /** Material keys accepted for menu items: existing, non-legacy items other than air. */
    static boolean isItem(String key) {
        Material material = Material.matchMaterial(key);
        return material != null && !material.isLegacy() && material.isItem() && !material.isAir();
    }

    private Result<MenuCatalog> loadCatalog() {
        return MenuCatalog.load(plugin.getDataFolder().toPath(), MenuModule::isItem, allowedCommands.get(),
                context.variables().definitions());
    }

    @Override
    public void start() {
        Result<MenuCatalog> loaded = loadCatalog();
        if (!loaded.isAccepted()) {
            logProblems("Menu files rejected", loaded.problems());
            throw new IllegalStateException("menu files are invalid");
        }
        catalog = loaded.orThrow();
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        plugin.getLogger().info("Loaded " + catalog.menus().size() + " menu(s)");
    }

    @Override
    public void stop() {
        closeAll();
        HandlerList.unregisterAll(this);
        inputs.values().forEach(pending -> tasks.cancel(pending.timeout()));
        inputs.clear();
    }

    @Override
    public List<Problem> reload() {
        Result<MenuCatalog> loaded = loadCatalog();
        if (!loaded.isAccepted()) {
            return loaded.problems();
        }
        closeAll();
        catalog = loaded.orThrow();
        return List.of();
    }

    private void closeAll() {
        for (MenuSession session : List.copyOf(sessions.values())) {
            Player viewer = plugin.getServer().getPlayer(session.viewer());
            if (viewer != null && viewer.getOpenInventory().getTopInventory().getHolder(false) == session) {
                viewer.closeInventory();
            }
            finish(session, viewer);
        }
    }

    public Collection<MenuDefinition> menus() {
        return catalog.menus().values();
    }

    public Optional<MenuDefinition> menu(Identifier id) {
        return catalog.menu(id);
    }

    public boolean mayOpen(Player viewer, MenuDefinition menu) {
        return menu.permissionNode().map(viewer::hasPermission).orElse(true);
    }

    public Optional<MenuSession> session(UUID viewer) {
        return Optional.ofNullable(sessions.get(viewer));
    }

    /** Opens a menu after checking its permission, open condition and open actions for the viewer. */
    public Result<MenuSession> open(Player viewer, Identifier id) {
        MenuDefinition menu = catalog.menu(id).orElse(null);
        if (menu == null) {
            return Result.rejected(id.toString(), "unknown-menu", "no such menu");
        }
        if (!mayOpen(viewer, menu)) {
            return Result.rejected(id.toString(), "no-permission", "viewer may not open this menu");
        }
        if (menu.openCondition() != null) {
            Result<Boolean> allowed = engine().test(menu.openCondition(),
                    VariableContext.player(viewer.getUniqueId()), LogicEngine.Limits.DEFAULT, context.world(viewer));
            if (!allowed.isAccepted()) {
                logProblems("Open condition of " + id + " could not be evaluated", allowed.problems());
                return Result.rejected(allowed.problems());
            }
            if (!allowed.orThrow()) {
                return Result.rejected(id.toString(), "condition", "open condition is not met");
            }
        }
        MenuSession session = new MenuSession(viewer.getUniqueId(), menu, render(menu.title(), viewer, null));
        VariableContext variables = VariableContext.session(viewer.getUniqueId(), session.id());
        if (!menu.onOpen().isEmpty()) {
            PlayerWorld world = context.world(viewer);
            Outcome outcome = engine().run(menu.onOpen(), variables, LogicEngine.Limits.DEFAULT, world, world);
            if (!(outcome instanceof Outcome.Committed committed)) {
                store().closeSession(session.id());
                report(viewer, menu, outcome);
                return Result.rejected(id.toString(), "on-open", "open actions did not complete");
            }
            perform(viewer, session, committed.effects(), false);
        }
        draw(session, viewer);
        sessions.put(viewer.getUniqueId(), session);
        viewer.openInventory(session.getInventory());
        return Result.accepted(session);
    }

    private Component render(String template, Player viewer, MenuSession session) {
        return context.text().render(template, viewer, context.world(viewer), session == null ? null : session.id());
    }

    private boolean test(Condition condition, VariableContext variables, MenuDefinition menu, Player viewer) {
        if (condition == null) {
            return true;
        }
        Result<Boolean> result = engine().test(condition, variables, LogicEngine.Limits.DEFAULT, context.world(viewer));
        if (!result.isAccepted()) {
            logProblems("Condition in " + menu.id() + " could not be evaluated", result.problems());
            return false;
        }
        return result.orThrow();
    }

    private void draw(MenuSession session, Player viewer) {
        VariableContext variables = VariableContext.session(viewer.getUniqueId(), session.id());
        MenuDefinition menu = session.menu();
        session.getInventory().clear();
        for (MenuItem item : menu.items()) {
            if (!test(item.visible(), variables, menu, viewer)) {
                continue;
            }
            ItemLook look = item.base();
            for (MenuItem.State state : item.states()) {
                if (test(state.when(), variables, menu, viewer)) {
                    look = state.look().over(item.base());
                    break;
                }
            }
            ItemStack stack = build(look, viewer, session);
            for (int slot : item.slots()) {
                session.getInventory().setItem(slot, stack.clone());
            }
        }
    }

    private ItemStack build(ItemLook look, Player viewer, MenuSession session) {
        Material material = Material.matchMaterial(look.material());
        ItemStack stack = new ItemStack(material == null ? Material.BARRIER : material, look.amount());
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            if (look.name() != null) {
                meta.displayName(plain(render(look.name(), viewer, session)));
            }
            if (!look.lore().isEmpty()) {
                List<Component> lore = new ArrayList<>();
                look.lore().forEach(line -> lore.add(plain(render(line, viewer, session))));
                meta.lore(lore);
            }
            if (Boolean.TRUE.equals(look.glow())) {
                meta.setEnchantmentGlintOverride(true);
            }
            if (look.model() != null) {
                meta.setItemModel(NamespacedKey.fromString(look.model()));
            }
            if (look.customModelData() != null) {
                meta.setCustomModelData(look.customModelData());
            }
            if (look.head() != null && meta instanceof SkullMeta skull) {
                skull.setPlayerProfile(profile(look.head(), viewer));
            }
            meta.addItemFlags(ItemFlag.HIDE_ADDITIONAL_TOOLTIP, ItemFlag.HIDE_ENCHANTS);
            stack.setItemMeta(meta);
        }
        return stack;
    }

    private static PlayerProfile profile(String head, Player viewer) {
        if (head.equals(HeadSpec.VIEWER)) {
            return viewer.getPlayerProfile();
        }
        UUID id = HeadSpec.uuid(head);
        if (id != null) {
            return viewer.getServer().createProfile(id);
        }
        PlayerProfile profile = viewer.getServer().createProfile(
                UUID.nameUUIDFromBytes(head.getBytes(StandardCharsets.UTF_8)), "VibeCore");
        profile.setProperty(new ProfileProperty("textures", head));
        return profile;
    }

    private static Component plain(Component component) {
        return Component.empty().decoration(TextDecoration.ITALIC, false).append(component);
    }

    private static ClickChannel channel(org.bukkit.event.inventory.ClickType click) {
        return switch (click) {
            case LEFT -> ClickChannel.LEFT;
            case RIGHT -> ClickChannel.RIGHT;
            case SHIFT_LEFT -> ClickChannel.SHIFT_LEFT;
            case SHIFT_RIGHT -> ClickChannel.SHIFT_RIGHT;
            case MIDDLE -> ClickChannel.MIDDLE;
            default -> null;
        };
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder(false) instanceof MenuSession session)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player viewer) || sessions.get(viewer.getUniqueId()) != session
                || event.getClickedInventory() != session.getInventory()) {
            return;
        }
        ClickChannel channel = channel(event.getClick());
        MenuItem item = channel == null ? null : session.item(event.getSlot());
        if (item == null || item.actionsFor(channel).isEmpty()) {
            return;
        }
        VariableContext variables = VariableContext.session(viewer.getUniqueId(), session.id());
        if (!test(item.visible(), variables, session.menu(), viewer)
                || !session.tryBeginClick(System.nanoTime(), DEBOUNCE_NANOS)) {
            return;
        }
        try {
            if (!mayOpen(viewer, session.menu())) {
                messages.send(viewer, "menu.denied");
                tasks.runLater(() -> closeIfCurrent(viewer, session), 1);
                return;
            }
            PlayerWorld world = context.world(viewer);
            Outcome outcome = engine().run(item.actionsFor(channel), variables, LogicEngine.Limits.DEFAULT, world, world);
            if (outcome instanceof Outcome.Committed committed) {
                perform(viewer, session, committed.effects(), true);
            } else {
                report(viewer, session.menu(), outcome);
            }
            if (!session.isClosed()) {
                draw(session, viewer);
            }
        } finally {
            session.endClick();
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder(false) instanceof MenuSession) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent event) {
        if (event.getInventory().getHolder(false) instanceof MenuSession session) {
            finish(session, event.getPlayer() instanceof Player player ? player : null);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onQuit(PlayerQuitEvent event) {
        UUID viewer = event.getPlayer().getUniqueId();
        PendingInput pending = inputs.remove(viewer);
        if (pending != null) {
            tasks.cancel(pending.timeout());
        }
        MenuSession session = sessions.get(viewer);
        if (session != null) {
            finish(session, event.getPlayer());
        }
    }

    /** Ends a session exactly once: runs its close actions, then drops its session values. */
    private void finish(MenuSession session, Player viewer) {
        if (!session.close()) {
            return;
        }
        sessions.remove(session.viewer(), session);
        List<Action> onClose = session.menu().onClose();
        if (!onClose.isEmpty()) {
            VariableContext variables = VariableContext.session(session.viewer(), session.id());
            Outcome outcome;
            if (viewer != null) {
                PlayerWorld world = context.world(viewer);
                outcome = engine().run(onClose, variables, LogicEngine.Limits.DEFAULT, world, world);
            } else {
                outcome = engine().run(onClose, variables, LogicEngine.Limits.DEFAULT, Facts.NONE, effects ->
                        TransactionPlan.of(effects).empty() ? SideEffects.NONE.perform(effects)
                                : Result.rejected("on-close", "viewer-offline", "money and items need the viewer"));
            }
            if (viewer != null && viewer.isOnline()) {
                if (outcome instanceof Outcome.Committed committed) {
                    perform(viewer, session, committed.effects(), false);
                } else {
                    report(viewer, session.menu(), outcome);
                }
            }
        }
        store().closeSession(session.id());
    }

    private void closeIfCurrent(Player viewer, MenuSession session) {
        if (viewer.isOnline() && viewer.getOpenInventory().getTopInventory().getHolder(false) == session) {
            viewer.closeInventory();
        }
    }

    /**
     * Performs committed effects. Navigation effects run on the next tick and
     * only from clicks, so open and close actions can never reopen a menu.
     */
    private void perform(Player viewer, MenuSession session, List<Action.Emit> effects, boolean navigation) {
        for (Action.Emit effect : effects) {
            Map<String, String> arguments = effect.arguments();
            switch (effect.type()) {
                case "message" -> viewer.sendMessage(render(arguments.get("text"), viewer, session));
                case "sound" -> viewer.playSound(viewer.getLocation(), arguments.get("sound"),
                        MenuEffect.number(arguments.get("volume"), 1f), MenuEffect.number(arguments.get("pitch"), 1f));
                case "close" -> {
                    if (navigation) {
                        tasks.runLater(() -> closeIfCurrent(viewer, session), 1);
                    }
                }
                case "open" -> {
                    if (navigation) {
                        Identifier target = Identifier.parse("menu", arguments.get("menu")).orThrow();
                        tasks.runLater(() -> {
                            if (viewer.isOnline() && sessions.get(viewer.getUniqueId()) == session) {
                                Result<MenuSession> opened = open(viewer, target);
                                if (!opened.isAccepted() && opened.problems().getFirst().code().equals("no-permission")) {
                                    messages.send(viewer, "menu.denied");
                                }
                            }
                        }, 1);
                    }
                }
                case "command" -> runCommand(viewer, session, arguments);
                case "input" -> {
                    if (navigation) {
                        requestInput(viewer, session, arguments);
                    }
                }
                default -> {
                    // refresh: the menu is redrawn after every committed click
                }
            }
        }
    }

    /**
     * Runs an owner-written command after the chain committed. Console commands
     * are logged with the menu and viewer for auditing; player commands run with
     * the viewer's own permissions only.
     */
    private void runCommand(Player viewer, MenuSession session, Map<String, String> arguments) {
        MenuDefinition menu = session.menu();
        CommandTemplate template = CommandTemplate.parse("run", arguments.get("run")).orThrow();
        if (!allowedCommands.get().contains(template.root())) {
            plugin.getLogger().warning("Menu " + menu.id() + " tried to run '" + template.root()
                    + "', which is no longer allowed");
            return;
        }
        VariableContext variables = VariableContext.session(viewer.getUniqueId(), session.id());
        Result<String> line = template.render(viewer.getName(), viewer.getUniqueId(), key -> store().get(key, variables));
        if (!line.isAccepted()) {
            logProblems("Command in " + menu.id() + " was not run", line.problems());
            return;
        }
        if ("console".equals(arguments.get("as"))) {
            plugin.getLogger().info("Menu " + menu.id() + " runs console command for " + viewer.getName() + ": "
                    + line.orThrow());
            plugin.getServer().dispatchCommand(plugin.getServer().getConsoleSender(), line.orThrow());
        } else {
            viewer.performCommand(line.orThrow());
        }
    }

    /** A chat answer the viewer owes to a menu. */
    private record PendingInput(Identifier menu, Identifier variable, BukkitTask timeout) {
    }

    /**
     * Closes the menu and waits for one chat message. The message is never
     * broadcast; it is validated against the variable and the menu reopens.
     */
    private void requestInput(Player viewer, MenuSession session, Map<String, String> arguments) {
        Identifier variable = Identifier.parse("var", arguments.get("var")).orThrow();
        long timeoutTicks = 20L * (long) Double.parseDouble(arguments.getOrDefault("timeout", "30"));
        UUID id = viewer.getUniqueId();
        tasks.runLater(() -> {
            if (!viewer.isOnline()) {
                return;
            }
            closeIfCurrent(viewer, session);
            BukkitTask timeout = tasks.runLater(() -> {
                if (inputs.remove(id) != null && viewer.isOnline()) {
                    messages.send(viewer, "menu.input-timeout");
                }
            }, timeoutTicks);
            PendingInput previous = inputs.put(id, new PendingInput(session.menu().id(), variable, timeout));
            if (previous != null) {
                tasks.cancel(previous.timeout());
            }
            viewer.sendMessage(render(arguments.get("prompt"), viewer, null));
            messages.send(viewer, "menu.input-hint");
        }, 1);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncChatEvent event) {
        PendingInput pending = inputs.remove(event.getPlayer().getUniqueId());
        if (pending == null) {
            return;
        }
        event.setCancelled(true);
        event.viewers().clear();
        String text = PlainTextComponentSerializer.plainText().serialize(event.message());
        Player viewer = event.getPlayer();
        tasks.runOnMain(() -> completeInput(viewer, pending, text));
    }

    private void completeInput(Player viewer, PendingInput pending, String text) {
        tasks.cancel(pending.timeout());
        if (!viewer.isOnline()) {
            return;
        }
        if (text.equalsIgnoreCase("cancel") || text.equalsIgnoreCase("iptal")) {
            messages.send(viewer, "menu.input-cancelled");
        } else {
            Result<VariableValue> stored = text.length() > 256
                    ? Result.rejected("input", "length", "longer than 256 characters")
                    : store().definition(pending.variable()).isAccepted()
                    ? store().definition(pending.variable()).orThrow().parse(text)
                    : Result.rejected("input", "unknown-variable", "the variable no longer exists");
            if (stored.isAccepted()) {
                stored = store().set(pending.variable(), VariableContext.player(viewer.getUniqueId()), stored.orThrow());
            }
            if (!stored.isAccepted()) {
                messages.send(viewer, "menu.input-invalid", "reason", stored.problems().getFirst().detail());
            }
        }
        if (catalog.menu(pending.menu()).isPresent()) {
            Result<MenuSession> reopened = open(viewer, pending.menu());
            if (!reopened.isAccepted() && reopened.problems().getFirst().code().equals("no-permission")) {
                messages.send(viewer, "menu.denied");
            }
        }
    }

    private void report(Player viewer, MenuDefinition menu, Outcome outcome) {
        switch (outcome) {
            case Outcome.Failed failed -> viewer.sendMessage(render(failed.reason(), viewer, null));
            case Outcome.Rejected rejected -> {
                logProblems("Action chain in " + menu.id() + " was rejected", rejected.problems());
                messages.send(viewer, "menu.action-error");
            }
            case Outcome.Committed ignored -> {
            }
        }
    }

    private void logProblems(String headline, List<Problem> problems) {
        plugin.getLogger().warning(headline + ":");
        problems.forEach(problem -> plugin.getLogger().warning("  " + problem));
    }
}
