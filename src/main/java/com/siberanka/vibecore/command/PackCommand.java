package com.siberanka.vibecore.command;

import com.siberanka.vibecore.VibeCorePlugin;
import com.siberanka.vibecore.core.module.ModuleId;
import com.siberanka.vibecore.core.text.Messages;
import com.siberanka.vibecore.core.validation.Problem;
import com.siberanka.vibecore.pack.PackModule;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** {@code /vibecore pack build} and {@code status}. */
final class PackCommand {

    static final String STATUS = "vibecore.pack.status";
    static final String BUILD = "vibecore.pack.build";
    private static final int PROBLEMS_SHOWN = 8;

    private final VibeCorePlugin plugin;
    private final Messages messages;

    PackCommand(VibeCorePlugin plugin, Messages messages) {
        this.plugin = plugin;
        this.messages = messages;
    }

    void execute(CommandSender sender, String[] args) {
        Optional<PackModule> module = plugin.modules().running(ModuleId.PACK, PackModule.class);
        if (module.isEmpty()) {
            messages.send(sender, "pack.disabled");
            return;
        }
        String action = args.length == 0 ? "status" : args[0].toLowerCase(Locale.ROOT);
        switch (action) {
            case "status" -> status(sender, module.get());
            case "build" -> build(sender, module.get());
            default -> messages.send(sender, "pack.usage");
        }
    }

    private void status(CommandSender sender, PackModule module) {
        if (module.building()) {
            messages.send(sender, "pack.building");
        }
        module.lastBuild().ifPresentOrElse(build -> messages.send(sender, "pack.status",
                        "hash", build.sha1(), "size", Integer.toString(build.bytes()),
                        "glyphs", Integer.toString(build.glyphs()), "items", Integer.toString(build.items()),
                        "time", build.builtAt().toString()),
                () -> messages.send(sender, "pack.none"));
        problems(sender, module.lastProblems());
    }

    private void build(CommandSender sender, PackModule module) {
        if (!sender.hasPermission(BUILD)) {
            messages.send(sender, "command.no-permission");
            return;
        }
        UUID requester = sender instanceof Player player ? player.getUniqueId() : null;
        boolean started = module.build(result -> {
            CommandSender target = requester == null ? sender : plugin.getServer().getPlayer(requester);
            if (target == null) {
                return;
            }
            if (result.isAccepted()) {
                var build = result.orThrow();
                messages.send(target, "pack.built", "hash", build.sha1(), "size", Integer.toString(build.bytes()),
                        "glyphs", Integer.toString(build.glyphs()), "items", Integer.toString(build.items()));
            } else {
                messages.send(target, "pack.rejected", "count", Integer.toString(result.problems().size()));
                problems(target, result.problems());
            }
        });
        messages.send(sender, started ? "pack.building" : "command.busy");
    }

    private void problems(CommandSender sender, List<Problem> problems) {
        problems.stream().limit(PROBLEMS_SHOWN).forEach(problem ->
                messages.sendLine(sender, "reload.problem", "problem", problem.toString()));
    }

    List<String> complete(CommandSender sender, String[] args) {
        if (args.length != 1) {
            return List.of();
        }
        return List.of("build", "status").stream()
                .filter(action -> action.startsWith(args[0].toLowerCase(Locale.ROOT)))
                .filter(action -> !action.equals("build") || sender.hasPermission(BUILD))
                .toList();
    }
}
