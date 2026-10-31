package com.siberanka.vibecore.command;

import com.siberanka.vibecore.VibeCorePlugin;
import com.siberanka.vibecore.core.module.ModuleId;
import com.siberanka.vibecore.core.text.Messages;
import com.siberanka.vibecore.core.validation.Result;
import com.siberanka.vibecore.menu.MenuDefinition;
import com.siberanka.vibecore.menu.MenuModule;
import com.siberanka.vibecore.menu.MenuSession;
import com.siberanka.vibecore.project.Identifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * {@code /vibecore menu open <id> [player]}, {@code close [player]} and
 * {@code list}. Acting for another player needs the separate {@code .others}
 * permission, and the menu's own permission is always checked against the
 * viewer, never the sender.
 */
final class MenuCommand {

    static final String OPEN = "vibecore.menu.open";
    static final String OPEN_OTHERS = "vibecore.menu.open.others";
    static final String CLOSE_OTHERS = "vibecore.menu.close.others";
    static final String LIST = "vibecore.menu.list";

    private final VibeCorePlugin plugin;
    private final Messages messages;

    MenuCommand(VibeCorePlugin plugin, Messages messages) {
        this.plugin = plugin;
        this.messages = messages;
    }

    private Optional<MenuModule> module() {
        return plugin.modules().running(ModuleId.MENU, MenuModule.class);
    }

    void execute(CommandSender sender, String[] args) {
        Optional<MenuModule> module = module();
        if (module.isEmpty()) {
            messages.send(sender, "menu.disabled");
            return;
        }
        String action = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        switch (action) {
            case "open" -> open(sender, module.get(), args);
            case "close" -> close(sender, args);
            case "list" -> list(sender, module.get());
            default -> messages.send(sender, "menu.usage");
        }
    }

    /** Resolves the viewer: the sender, or a named player when the sender holds {@code othersPermission}. */
    private Player target(CommandSender sender, String[] args, int index, String othersPermission) {
        if (args.length > index) {
            if (!sender.hasPermission(othersPermission)) {
                messages.send(sender, "command.no-permission");
                return null;
            }
            Player player = plugin.getServer().getPlayerExact(args[index]);
            if (player == null) {
                messages.send(sender, "menu.player-missing");
            }
            return player;
        }
        if (sender instanceof Player player) {
            return player;
        }
        messages.send(sender, "menu.console-target");
        return null;
    }

    private void open(CommandSender sender, MenuModule module, String[] args) {
        if (args.length < 2 || args.length > 3) {
            messages.send(sender, "menu.usage");
            return;
        }
        Result<Identifier> id = Identifier.parse("menu", args[1].toLowerCase(Locale.ROOT));
        Optional<MenuDefinition> menu = id.isAccepted() ? module.menu(id.orThrow()) : Optional.empty();
        if (menu.isEmpty()) {
            messages.send(sender, "menu.unknown", "menu", args[1]);
            return;
        }
        Player viewer = target(sender, args, 2, OPEN_OTHERS);
        if (viewer == null) {
            return;
        }
        Result<MenuSession> opened = module.open(viewer, menu.get().id());
        boolean self = viewer.equals(sender);
        if (opened.isAccepted()) {
            if (!self) {
                messages.send(sender, "menu.opened-for", "menu", menu.get().id().toString(), "player", viewer.getName());
            }
            return;
        }
        String code = opened.problems().getFirst().code();
        if (code.equals("no-permission") || code.equals("condition")) {
            if (self) {
                messages.send(sender, "menu.denied");
            } else {
                messages.send(sender, "menu.target-denied", "player", viewer.getName());
            }
        } else if (!self) {
            messages.send(sender, "menu.action-error");
        }
    }

    private void close(CommandSender sender, String[] args) {
        if (args.length > 2) {
            messages.send(sender, "menu.usage");
            return;
        }
        Player viewer = target(sender, args, 1, CLOSE_OTHERS);
        if (viewer == null) {
            return;
        }
        if (!(viewer.getOpenInventory().getTopInventory().getHolder(false) instanceof MenuSession)) {
            messages.send(sender, "menu.not-open", "player", viewer.getName());
            return;
        }
        viewer.closeInventory();
        if (!viewer.equals(sender)) {
            messages.send(sender, "menu.closed-for", "player", viewer.getName());
        }
    }

    private void list(CommandSender sender, MenuModule module) {
        if (!sender.hasPermission(LIST)) {
            messages.send(sender, "command.no-permission");
            return;
        }
        List<MenuDefinition> menus = module.menus().stream()
                .sorted((a, b) -> a.id().compareTo(b.id())).toList();
        messages.send(sender, "menu.list-header", "count", Integer.toString(menus.size()));
        for (MenuDefinition menu : menus) {
            messages.sendLine(sender, "menu.list-entry", "menu", menu.id().toString(), "rows", Integer.toString(menu.rows()));
        }
    }

    List<String> complete(CommandSender sender, String[] args) {
        List<String> matches = new ArrayList<>();
        Optional<MenuModule> module = module();
        if (module.isEmpty()) {
            return matches;
        }
        String typed = args[args.length - 1].toLowerCase(Locale.ROOT);
        if (args.length == 1) {
            for (String action : List.of("open", "close", "list")) {
                if (action.startsWith(typed) && (!action.equals("list") || sender.hasPermission(LIST))) {
                    matches.add(action);
                }
            }
        } else if (args.length == 2 && args[0].equalsIgnoreCase("open")) {
            for (MenuDefinition menu : module.get().menus()) {
                boolean visible = !(sender instanceof Player player) || module.get().mayOpen(player, menu)
                        || sender.hasPermission(OPEN_OTHERS);
                String name = menu.id().namespace().equals(Identifier.DEFAULT_NAMESPACE)
                        ? menu.id().path() : menu.id().toString();
                if (visible && name.startsWith(typed)) {
                    matches.add(name);
                }
            }
        } else if ((args.length == 3 && args[0].equalsIgnoreCase("open") && sender.hasPermission(OPEN_OTHERS))
                || (args.length == 2 && args[0].equalsIgnoreCase("close") && sender.hasPermission(CLOSE_OTHERS))) {
            for (Player player : plugin.getServer().getOnlinePlayers()) {
                if (player.getName().toLowerCase(Locale.ROOT).startsWith(typed)
                        && (!(sender instanceof Player self) || self.canSee(player))) {
                    matches.add(player.getName());
                }
            }
        }
        matches.sort(String::compareTo);
        return matches;
    }
}
