package com.siberanka.vibecore.menu;

import com.siberanka.vibecore.core.io.AtomicFiles;
import com.siberanka.vibecore.core.module.ModuleId;
import com.siberanka.vibecore.core.module.VibeModule;
import com.siberanka.vibecore.core.task.BoundedWorkers;
import com.siberanka.vibecore.core.task.TaskTracker;
import com.siberanka.vibecore.core.text.Messages;
import com.siberanka.vibecore.core.validation.Problem;
import com.siberanka.vibecore.core.validation.Result;
import com.siberanka.vibecore.integration.placeholder.PlaceholderApi;
import com.siberanka.vibecore.logic.Action;
import com.siberanka.vibecore.logic.Condition;
import com.siberanka.vibecore.logic.Facts;
import com.siberanka.vibecore.logic.LogicEngine;
import com.siberanka.vibecore.logic.Outcome;
import com.siberanka.vibecore.logic.SideEffects;
import com.siberanka.vibecore.project.Identifier;
import com.siberanka.vibecore.variable.VariableContext;
import com.siberanka.vibecore.variable.VariableStore;
import com.siberanka.vibecore.variable.VariableValue;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.random.RandomGenerator;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.Tag;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Material;
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
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * Inventory menus. Every interaction with an open menu is cancelled; only a
 * permitted click on a visible item runs that item's action chain, which
 * re-checks its own conditions and commits its variable changes all or none.
 */
public final class MenuModule implements VibeModule, Listener {

    private static final long DEBOUNCE_NANOS = TimeUnit.MILLISECONDS.toNanos(150);
    private static final long SAVE_PERIOD_TICKS = 20L * 60 * 5;
    private static final int MAX_VALUES_PER_PLAYER = 256;

    private final JavaPlugin plugin;
    private final TaskTracker tasks;
    private final BoundedWorkers workers;
    private final Messages messages;
    private final Supplier<Set<String>> allowedCommands;
    private final Path dataFile;
    private final Path ledger;
    private final PlaceholderApi placeholders;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();
    private final Map<UUID, MenuSession> sessions = new ConcurrentHashMap<>();
    private final Object saveLock = new Object();
    private final AtomicLong saveSequence = new AtomicLong();
    private long lastWrittenSequence;

    private volatile MenuCatalog catalog;
    private volatile VariableStore store;
    private volatile LogicEngine engine;
    private BukkitTask saveTask;

    public MenuModule(JavaPlugin plugin, TaskTracker tasks, BoundedWorkers workers, Messages messages,
                      Supplier<Set<String>> allowedCommands) {
        this.allowedCommands = allowedCommands;
        this.plugin = plugin;
        this.tasks = tasks;
        this.workers = workers;
        this.messages = messages;
        this.dataFile = plugin.getDataFolder().toPath().resolve("data").resolve("variables.txt");
        this.ledger = plugin.getDataFolder().toPath().resolve("data").resolve("transactions.log");
        this.placeholders = new PlaceholderApi(plugin.getServer());
    }

    private PlayerWorld world(Player viewer) {
        return new PlayerWorld(viewer, messages, plugin.getLogger(), ledger);
    }

    @Override
    public ModuleId id() {
        return ModuleId.MENU;
    }

    /** Material keys accepted for menu items: existing, non-legacy items other than air. */
    static boolean isItem(String key) {
        Material material = Material.matchMaterial(key);
        return material != null && !material.isLegacy() && material.isItem() && !material.isAir();
    }

    @Override
    public void start() throws IOException {
        Result<MenuCatalog> loaded = MenuCatalog.load(plugin.getDataFolder().toPath(), MenuModule::isItem,
                allowedCommands.get());
        if (!loaded.isAccepted()) {
            logProblems("Menu files rejected", loaded.problems());
            throw new IllegalStateException("menu files are invalid");
        }
        VariableStore created = VariableStore.create(loaded.orThrow().variables(), MAX_VALUES_PER_PLAYER).orThrow();
        if (Files.isRegularFile(dataFile, LinkOption.NOFOLLOW_LINKS)) {
            Result<Integer> restored = created.loadPersistent(Files.readString(dataFile, StandardCharsets.UTF_8));
            if (!restored.isAccepted()) {
                logProblems("Stored variables are unreadable; refusing to start so they are not overwritten",
                        restored.problems());
                throw new IllegalStateException("variable data is invalid");
            }
        }
        catalog = loaded.orThrow();
        store = created;
        engine = new LogicEngine(created, RandomGenerator.getDefault());
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        saveTask = tasks.runTimer(this::saveInBackground, SAVE_PERIOD_TICKS, SAVE_PERIOD_TICKS);
        plugin.getLogger().info("Loaded " + catalog.menus().size() + " menu(s) and "
                + catalog.variables().size() + " variable(s)");
    }

    @Override
    public void stop() {
        for (MenuSession session : List.copyOf(sessions.values())) {
            Player viewer = plugin.getServer().getPlayer(session.viewer());
            if (viewer != null && viewer.getOpenInventory().getTopInventory().getHolder(false) == session) {
                viewer.closeInventory();
            }
            finish(session, viewer);
        }
        HandlerList.unregisterAll(this);
        if (saveTask != null) {
            tasks.cancel(saveTask);
            saveTask = null;
        }
        if (store != null) {
            saveNow();
        }
    }

    @Override
    public List<Problem> reload() {
        Result<MenuCatalog> loaded = MenuCatalog.load(plugin.getDataFolder().toPath(), MenuModule::isItem,
                allowedCommands.get());
        if (!loaded.isAccepted()) {
            return loaded.problems();
        }
        MenuCatalog next = loaded.orThrow();
        if (!next.variables().equals(catalog.variables())) {
            Result<VariableStore> created = VariableStore.create(next.variables(), MAX_VALUES_PER_PLAYER);
            if (!created.isAccepted()) {
                return created.problems();
            }
            Result<Integer> moved = created.orThrow().loadPersistent(store.serializePersistent());
            if (!moved.isAccepted()) {
                store.markPersistentDirty();
                return moved.problems();
            }
            closeAll();
            store = created.orThrow();
            store.markPersistentDirty();
            engine = new LogicEngine(store, RandomGenerator.getDefault());
        } else {
            closeAll();
        }
        catalog = next;
        return List.of();
    }

    private void closeAll() {
        for (MenuSession session : List.copyOf(sessions.values())) {
            Player viewer = plugin.getServer().getPlayer(session.viewer());
            if (viewer != null) {
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
            Result<Boolean> allowed = engine.test(menu.openCondition(),
                    VariableContext.player(viewer.getUniqueId()), LogicEngine.Limits.DEFAULT, world(viewer));
            if (!allowed.isAccepted()) {
                logProblems("Open condition of " + id + " could not be evaluated", allowed.problems());
                return Result.rejected(allowed.problems());
            }
            if (!allowed.orThrow()) {
                return Result.rejected(id.toString(), "condition", "open condition is not met");
            }
        }
        MenuSession session = new MenuSession(viewer.getUniqueId(), menu, render(menu.title(), viewer, null));
        VariableContext context = VariableContext.session(viewer.getUniqueId(), session.id());
        if (!menu.onOpen().isEmpty()) {
            PlayerWorld world = world(viewer);
            Outcome outcome = engine.run(menu.onOpen(), context, LogicEngine.Limits.DEFAULT, world, world);
            if (!(outcome instanceof Outcome.Committed committed)) {
                store.closeSession(session.id());
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

    /** Text with {@code <player>} and {@code <var:key>} resolved as plain text for the viewer. */
    private Component render(String template, Player viewer, MenuSession session) {
        UUID sessionId = session == null ? null : session.id();
        VariableContext context = VariableContext.session(viewer.getUniqueId(), sessionId);
        TagResolver variables = TagResolver.resolver("var", (arguments, ignored) -> {
            StringBuilder key = new StringBuilder();
            while (arguments.hasNext()) {
                key.append(key.isEmpty() ? "" : ":").append(arguments.pop().value());
            }
            Result<Identifier> id = Identifier.parse("var", key.toString());
            String value = id.isAccepted()
                    ? Optional.of(store.get(id.orThrow(), context)).filter(Result::isAccepted)
                    .map(result -> result.orThrow().canonical()).orElse("?")
                    : "?";
            return Tag.inserting(Component.text(value));
        });
        PlayerWorld world = world(viewer);
        TagResolver data = TagResolver.resolver("data", (arguments, ignored) -> {
            String name = arguments.hasNext() ? arguments.pop().value() : "";
            Result<VariableValue> value = world.data(name);
            return Tag.inserting(Component.text(value.isAccepted() ? value.orThrow().canonical() : "?"));
        });
        TagResolver papi = TagResolver.resolver("papi", (arguments, ignored) -> {
            StringBuilder identifier = new StringBuilder();
            while (arguments.hasNext()) {
                identifier.append(identifier.isEmpty() ? "" : ":").append(arguments.pop().value());
            }
            String text = placeholders.expand(viewer, identifier.toString()).orElse("");
            return Tag.inserting(LegacyComponentSerializer.legacySection().deserialize(text));
        });
        return miniMessage.deserialize(template, variables, data, papi,
                Placeholder.unparsed("player", viewer.getName()));
    }

    private boolean test(Condition condition, VariableContext context, MenuDefinition menu, Player viewer) {
        if (condition == null) {
            return true;
        }
        Result<Boolean> result = engine.test(condition, context, LogicEngine.Limits.DEFAULT, world(viewer));
        if (!result.isAccepted()) {
            logProblems("Condition in " + menu.id() + " could not be evaluated", result.problems());
            return false;
        }
        return result.orThrow();
    }

    private void draw(MenuSession session, Player viewer) {
        VariableContext context = VariableContext.session(viewer.getUniqueId(), session.id());
        MenuDefinition menu = session.menu();
        session.getInventory().clear();
        for (MenuItem item : menu.items()) {
            if (!test(item.visible(), context, menu, viewer)) {
                continue;
            }
            ItemLook look = item.base();
            for (MenuItem.State state : item.states()) {
                if (test(state.when(), context, menu, viewer)) {
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
            meta.addItemFlags(ItemFlag.HIDE_ADDITIONAL_TOOLTIP, ItemFlag.HIDE_ENCHANTS);
            stack.setItemMeta(meta);
        }
        return stack;
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
        VariableContext context = VariableContext.session(viewer.getUniqueId(), session.id());
        if (!test(item.visible(), context, session.menu(), viewer)
                || !session.tryBeginClick(System.nanoTime(), DEBOUNCE_NANOS)) {
            return;
        }
        try {
            if (!mayOpen(viewer, session.menu())) {
                messages.send(viewer, "menu.denied");
                tasks.runLater(() -> closeIfCurrent(viewer, session), 1);
                return;
            }
            PlayerWorld world = world(viewer);
            Outcome outcome = engine.run(item.actionsFor(channel), context, LogicEngine.Limits.DEFAULT, world, world);
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

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        UUID viewer = event.getPlayer().getUniqueId();
        MenuSession session = sessions.get(viewer);
        if (session != null) {
            finish(session, event.getPlayer());
        }
        store.disconnect(viewer);
    }

    /** Ends a session exactly once: runs its close actions, then drops its session values. */
    private void finish(MenuSession session, Player viewer) {
        if (!session.close()) {
            return;
        }
        sessions.remove(session.viewer(), session);
        List<Action> onClose = session.menu().onClose();
        if (!onClose.isEmpty()) {
            VariableContext context = VariableContext.session(session.viewer(), session.id());
            Outcome outcome;
            if (viewer != null) {
                PlayerWorld world = world(viewer);
                outcome = engine.run(onClose, context, LogicEngine.Limits.DEFAULT, world, world);
            } else {
                outcome = engine.run(onClose, context, LogicEngine.Limits.DEFAULT, Facts.NONE, effects ->
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
        store.closeSession(session.id());
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
        VariableContext context = VariableContext.session(viewer.getUniqueId(), session.id());
        Result<String> line = template.render(viewer.getName(), viewer.getUniqueId(), key -> store.get(key, context));
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

    private void saveInBackground() {
        if (!store.persistentDirty()) {
            return;
        }
        String text = store.serializePersistent();
        long sequence = saveSequence.incrementAndGet();
        VariableStore owner = store;
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

    private void logProblems(String headline, List<Problem> problems) {
        plugin.getLogger().warning(headline + ":");
        problems.forEach(problem -> plugin.getLogger().warning("  " + problem));
    }
}
