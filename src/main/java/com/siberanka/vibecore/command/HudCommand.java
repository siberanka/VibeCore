package com.siberanka.vibecore.command;

import com.siberanka.vibecore.VibeCorePlugin;
import com.siberanka.vibecore.core.module.ModuleId;
import com.siberanka.vibecore.core.text.Messages;
import com.siberanka.vibecore.core.validation.Result;
import com.siberanka.vibecore.hud.HudDefinition;
import com.siberanka.vibecore.hud.HudModule;
import com.siberanka.vibecore.hud.PopupDefinition;
import com.siberanka.vibecore.project.Identifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * {@code /vibecore hud show|hide|reset|who [player]} and
 * {@code /vibecore popup <popup> <player|*>}. Acting on other players and
 * sending popups need separate permissions.
 */
final class HudCommand {

    static final String SELF = "vibecore.hud";
    static final String OTHERS = "vibecore.hud.others";
    static final String POPUP = "vibecore.popup";
    static final String POPUP_ALL = "vibecore.popup.all";
    private static final int MAX_POPUP_TARGETS = 500;
    private static final String EVERYONE = "*";

    private final VibeCorePlugin plugin;
    private final Messages messages;

    HudCommand(VibeCorePlugin plugin, Messages messages) {
        this.plugin = plugin;
        this.messages = messages;
    }

    private Optional<HudModule> module(CommandSender sender) {
        Optional<HudModule> module = plugin.modules().running(ModuleId.HUD, HudModule.class);
        if (module.isEmpty()) {
            messages.send(sender, "hud.disabled");
        }
        return module;
    }

    private Player target(CommandSender sender, String[] args, int index) {
        if (args.length > index) {
            if (!sender.hasPermission(OTHERS)) {
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

    void hud(CommandSender sender, String[] args) {
        Optional<HudModule> module = module(sender);
        if (module.isEmpty()) {
            return;
        }
        String action = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        switch (action) {
            case "show" -> show(sender, module.get(), args);
            case "hide", "reset", "who" -> {
                if (args.length > 2) {
                    messages.send(sender, "hud.usage");
                    return;
                }
                Player player = target(sender, args, 1);
                if (player == null) {
                    return;
                }
                if (action.equals("hide")) {
                    module.get().hide(player);
                    messages.send(sender, "hud.hidden", "player", player.getName());
                } else if (action.equals("reset")) {
                    module.get().reset(player);
                    messages.send(sender, "hud.reset", "player", player.getName());
                } else {
                    HudModule.Selection selection = module.get().selection(player);
                    messages.send(sender, "hud.who", "player", player.getName(),
                            "hud", selection.hud().map(Identifier::toString).orElse("-"),
                            "reason", selection.reason());
                }
            }
            default -> messages.send(sender, "hud.usage");
        }
    }

    private void show(CommandSender sender, HudModule module, String[] args) {
        if (args.length < 2 || args.length > 3) {
            messages.send(sender, "hud.usage");
            return;
        }
        Player player = target(sender, args, 2);
        if (player == null) {
            return;
        }
        Result<Identifier> id = Identifier.parse("hud", args[1].toLowerCase(Locale.ROOT));
        if (!id.isAccepted() || !module.show(player, id.orThrow())) {
            messages.send(sender, "hud.unknown", "hud", args[1]);
            return;
        }
        messages.send(sender, "hud.shown", "hud", id.orThrow().toString(), "player", player.getName());
    }

    void popup(CommandSender sender, String[] args) {
        Optional<HudModule> module = module(sender);
        if (module.isEmpty()) {
            return;
        }
        if (args.length != 2) {
            messages.send(sender, "popup.usage");
            return;
        }
        Result<Identifier> id = Identifier.parse("popup", args[0].toLowerCase(Locale.ROOT));
        PopupDefinition popup = id.isAccepted() ? module.get().catalog().popups().get(id.orThrow()) : null;
        if (popup == null) {
            messages.send(sender, "popup.unknown", "popup", args[0]);
            return;
        }
        List<Player> targets;
        if (args[1].equals(EVERYONE)) {
            if (!sender.hasPermission(POPUP_ALL)) {
                messages.send(sender, "command.no-permission");
                return;
            }
            targets = plugin.getServer().getOnlinePlayers().stream().limit(MAX_POPUP_TARGETS)
                    .map(Player.class::cast).toList();
        } else {
            Player player = plugin.getServer().getPlayerExact(args[1]);
            if (player == null) {
                messages.send(sender, "menu.player-missing");
                return;
            }
            targets = List.of(player);
        }
        int queued = 0;
        for (Player player : targets) {
            if (module.get().popup(player, popup)) {
                queued++;
            }
        }
        messages.send(sender, "popup.queued", "count", Integer.toString(queued),
                "full", Integer.toString(targets.size() - queued));
    }

    List<String> completeHud(CommandSender sender, String[] args) {
        List<String> matches = new ArrayList<>();
        Optional<HudModule> module = plugin.modules().running(ModuleId.HUD, HudModule.class);
        if (module.isEmpty()) {
            return matches;
        }
        String typed = args[args.length - 1].toLowerCase(Locale.ROOT);
        if (args.length == 1) {
            List.of("show", "hide", "reset", "who").stream().filter(action -> action.startsWith(typed))
                    .forEach(matches::add);
        } else if (args.length == 2 && args[0].equalsIgnoreCase("show")) {
            for (HudDefinition hud : module.get().catalog().huds()) {
                if (hud.id().path().startsWith(typed)) {
                    matches.add(hud.id().path());
                }
            }
        } else if (sender.hasPermission(OTHERS)) {
            players(sender, typed, matches);
        }
        return matches;
    }

    List<String> completePopup(CommandSender sender, String[] args) {
        List<String> matches = new ArrayList<>();
        Optional<HudModule> module = plugin.modules().running(ModuleId.HUD, HudModule.class);
        if (module.isEmpty()) {
            return matches;
        }
        String typed = args[args.length - 1].toLowerCase(Locale.ROOT);
        if (args.length == 1) {
            module.get().catalog().popups().keySet().stream().map(Identifier::path)
                    .filter(name -> name.startsWith(typed)).sorted().forEach(matches::add);
        } else if (args.length == 2) {
            if (sender.hasPermission(POPUP_ALL)) {
                matches.add(EVERYONE);
            }
            players(sender, typed, matches);
        }
        return matches;
    }

    private void players(CommandSender sender, String typed, List<String> matches) {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            if (player.getName().toLowerCase(Locale.ROOT).startsWith(typed)
                    && (!(sender instanceof Player self) || self.canSee(player))) {
                matches.add(player.getName());
            }
        }
    }
}
