package com.siberanka.vibecore.command;

import com.siberanka.vibecore.VibeCorePlugin;
import com.siberanka.vibecore.core.module.ModuleStatus;
import com.siberanka.vibecore.core.text.Messages;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;

/**
 * Root {@code /vibecore} command. Help and tab completion list only the
 * subcommands the sender is permitted to run, and every subcommand checks its
 * own permission again on execution.
 */
public final class VibeCoreCommand implements TabExecutor {

    private enum Subcommand {
        HELP("help", "vibecore.command.help"),
        STATUS("status", "vibecore.admin.status"),
        DIAGNOSTICS("diagnostics", "vibecore.admin.diagnostics"),
        RELOAD("reload", "vibecore.admin.reload"),
        MENU("menu", "vibecore.menu.open"),
        PACK("pack", "vibecore.pack.status");

        private final String label;
        private final String permission;

        Subcommand(String label, String permission) {
            this.label = label;
            this.permission = permission;
        }

        static Subcommand find(String label) {
            for (Subcommand subcommand : values()) {
                if (subcommand.label.equals(label)) {
                    return subcommand;
                }
            }
            return null;
        }
    }

    private final VibeCorePlugin plugin;
    private final Messages messages;
    private final MenuCommand menus;
    private final PackCommand packs;

    public VibeCoreCommand(VibeCorePlugin plugin, Messages messages) {
        this.plugin = plugin;
        this.messages = messages;
        this.menus = new MenuCommand(plugin, messages);
        this.packs = new PackCommand(plugin, messages);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        Subcommand subcommand = args.length == 0 ? Subcommand.HELP
                : Subcommand.find(args[0].toLowerCase(Locale.ROOT));
        if (subcommand == null) {
            messages.send(sender, "command.unknown");
            return true;
        }
        if (!sender.hasPermission(subcommand.permission)) {
            messages.send(sender, "command.no-permission");
            return true;
        }
        switch (subcommand) {
            case HELP -> help(sender);
            case STATUS -> status(sender);
            case DIAGNOSTICS -> diagnostics(sender);
            case RELOAD -> plugin.reload(sender);
            case MENU -> menus.execute(sender, Arrays.copyOfRange(args, 1, args.length));
            case PACK -> packs.execute(sender, Arrays.copyOfRange(args, 1, args.length));
        }
        return true;
    }

    private void help(CommandSender sender) {
        messages.sendLine(sender, "help.header");
        String locale = messages.localeOf(sender);
        for (Subcommand subcommand : Subcommand.values()) {
            if (sender.hasPermission(subcommand.permission)) {
                String description = plugin.catalog().template(locale, "help." + subcommand.label);
                messages.sendLine(sender, "help.entry", "command", subcommand.label, "description", description);
            }
        }
    }

    private void status(CommandSender sender) {
        messages.sendLine(sender, "status.header",
                "version", plugin.getPluginMeta().getVersion(),
                "revision", Long.toString(plugin.state().revision().revision()));
        String locale = messages.localeOf(sender);
        for (ModuleStatus status : plugin.modules().statuses()) {
            String stateKey = "status.state." + status.state().name().toLowerCase(Locale.ROOT).replace('_', '-');
            Component line = messages.render(locale, "status.module",
                    Placeholder.component("state", messages.render(locale, stateKey)),
                    "module", status.id().key(), "reason", status.reason());
            if (status.experimental()) {
                line = line.append(messages.render(locale, "status.experimental"));
            }
            sender.sendMessage(line);
        }
    }

    private void diagnostics(CommandSender sender) {
        messages.sendLine(sender, "diagnostics.header");
        for (Map.Entry<String, String> entry : plugin.diagnostics().snapshot().entrySet()) {
            messages.sendLine(sender, "diagnostics.entry", "key", entry.getKey(), "value", entry.getValue());
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        if (args.length > 1 && args[0].equalsIgnoreCase("menu") && sender.hasPermission(Subcommand.MENU.permission)) {
            return menus.complete(sender, Arrays.copyOfRange(args, 1, args.length));
        }
        if (args.length > 1 && args[0].equalsIgnoreCase("pack") && sender.hasPermission(Subcommand.PACK.permission)) {
            return packs.complete(sender, Arrays.copyOfRange(args, 1, args.length));
        }
        if (args.length != 1) {
            return List.of();
        }
        String typed = args[0].toLowerCase(Locale.ROOT);
        List<String> matches = new ArrayList<>();
        for (Subcommand subcommand : Subcommand.values()) {
            if (subcommand.label.startsWith(typed) && sender.hasPermission(subcommand.permission)) {
                matches.add(subcommand.label);
            }
        }
        return matches;
    }
}
