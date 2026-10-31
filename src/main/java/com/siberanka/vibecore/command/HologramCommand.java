package com.siberanka.vibecore.command;

import com.siberanka.vibecore.VibeCorePlugin;
import com.siberanka.vibecore.core.module.ModuleId;
import com.siberanka.vibecore.core.text.Messages;
import com.siberanka.vibecore.core.validation.Result;
import com.siberanka.vibecore.hologram.HologramModule;
import com.siberanka.vibecore.hologram.HologramPlacements;
import com.siberanka.vibecore.project.Identifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * {@code /vibecore hologram designs|list|nearest|place <design>|move <id>|tp <id>|remove <id>}.
 * Design identifiers and placement ids ({@code h1}, {@code h2}, …) are kept apart.
 */
final class HologramCommand {

    private static final List<String> ACTIONS = List.of("designs", "list", "nearest", "place", "move", "tp", "remove");
    private static final int LIST_LIMIT = 20;

    private final VibeCorePlugin plugin;
    private final Messages messages;

    HologramCommand(VibeCorePlugin plugin, Messages messages) {
        this.plugin = plugin;
        this.messages = messages;
    }

    void execute(CommandSender sender, String[] args) {
        Optional<HologramModule> module = plugin.modules().running(ModuleId.HOLOGRAM, HologramModule.class);
        if (module.isEmpty()) {
            messages.send(sender, "hologram.disabled");
            return;
        }
        String action = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        switch (action) {
            case "designs" -> {
                messages.send(sender, "hologram.designs", "count",
                        Integer.toString(module.get().catalog().designs().size()));
                module.get().catalog().designs().keySet().stream().sorted().forEach(id ->
                        messages.sendLine(sender, "hologram.entry", "entry", id.toString()));
            }
            case "list" -> {
                List<HologramPlacements.Placement> all = List.copyOf(module.get().placements().all());
                messages.send(sender, "hologram.placements", "count", Integer.toString(all.size()));
                all.stream().limit(LIST_LIMIT).forEach(placement -> messages.sendLine(sender, "hologram.entry",
                        "entry", describe(placement, module.get())));
            }
            case "nearest", "place", "move", "tp" -> {
                if (!(sender instanceof Player player)) {
                    messages.send(sender, "hologram.player-only");
                    return;
                }
                located(player, module.get(), action, args);
            }
            case "remove" -> {
                if (args.length != 2) {
                    messages.send(sender, "hologram.usage");
                } else if (module.get().remove(args[1])) {
                    messages.send(sender, "hologram.removed", "id", args[1].toLowerCase(Locale.ROOT));
                } else {
                    messages.send(sender, "hologram.unknown-placement", "id", args[1]);
                }
            }
            default -> messages.send(sender, "hologram.usage");
        }
    }

    private void located(Player player, HologramModule module, String action, String[] args) {
        Location here = player.getLocation();
        switch (action) {
            case "nearest" -> module.placements().nearest(here.getWorld().getUID(), here.getX(), here.getY(), here.getZ())
                    .ifPresentOrElse(placement -> messages.send(player, "hologram.nearest", "entry",
                                    describe(placement, module)),
                            () -> messages.send(player, "hologram.none-here"));
            case "place" -> {
                if (args.length != 2) {
                    messages.send(player, "hologram.usage");
                    return;
                }
                Result<Identifier> design = Identifier.parse("design", args[1].toLowerCase(Locale.ROOT));
                Result<HologramPlacements.Placement> placed = design.isAccepted()
                        ? module.place(design.orThrow(), here.clone().add(0, 2, 0)) : Result.rejected(design.problems());
                if (placed.isAccepted()) {
                    messages.send(player, "hologram.placed", "id", placed.orThrow().id(),
                            "design", placed.orThrow().design().toString());
                } else {
                    messages.send(player, "hologram.unknown-design", "design", args[1]);
                }
            }
            case "move" -> {
                if (args.length != 2) {
                    messages.send(player, "hologram.usage");
                    return;
                }
                module.move(args[1], here.clone().add(0, 2, 0)).ifPresentOrElse(
                        placement -> messages.send(player, "hologram.moved", "id", placement.id()),
                        () -> messages.send(player, "hologram.unknown-placement", "id", args[1]));
            }
            default -> {
                if (args.length != 2) {
                    messages.send(player, "hologram.usage");
                    return;
                }
                Optional<HologramPlacements.Placement> placement = module.placements().get(args[1]);
                World world = placement.map(found -> plugin.getServer().getWorld(found.world())).orElse(null);
                if (placement.isEmpty() || world == null) {
                    messages.send(player, "hologram.unknown-placement", "id", args[1]);
                    return;
                }
                player.teleportAsync(new Location(world, placement.get().x(), placement.get().y() - 2,
                        placement.get().z(), here.getYaw(), here.getPitch()));
            }
        }
    }

    private String describe(HologramPlacements.Placement placement, HologramModule module) {
        World world = plugin.getServer().getWorld(placement.world());
        String missing = module.catalog().design(placement.design()).isPresent() ? "" : " (design missing)";
        return String.format(Locale.ROOT, "%s %s @ %s %.1f %.1f %.1f%s", placement.id(), placement.design(),
                world == null ? placement.world() : world.getName(), placement.x(), placement.y(), placement.z(),
                missing);
    }

    List<String> complete(CommandSender sender, String[] args) {
        List<String> matches = new ArrayList<>();
        Optional<HologramModule> module = plugin.modules().running(ModuleId.HOLOGRAM, HologramModule.class);
        if (module.isEmpty()) {
            return matches;
        }
        String typed = args[args.length - 1].toLowerCase(Locale.ROOT);
        if (args.length == 1) {
            ACTIONS.stream().filter(action -> action.startsWith(typed)).forEach(matches::add);
        } else if (args.length == 2 && args[0].equalsIgnoreCase("place")) {
            module.get().catalog().designs().keySet().stream().map(Identifier::path)
                    .filter(name -> name.startsWith(typed)).sorted().forEach(matches::add);
        } else if (args.length == 2 && List.of("move", "tp", "remove").contains(args[0].toLowerCase(Locale.ROOT))) {
            module.get().placements().all().stream().map(HologramPlacements.Placement::id)
                    .filter(id -> id.startsWith(typed)).limit(50).forEach(matches::add);
        }
        return matches;
    }
}
