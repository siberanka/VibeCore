package com.siberanka.vibecore.command;

import com.siberanka.vibecore.VibeCorePlugin;
import com.siberanka.vibecore.badge.BadgeCatalog;
import com.siberanka.vibecore.badge.BadgeModule;
import com.siberanka.vibecore.core.module.ModuleId;
import com.siberanka.vibecore.core.text.Messages;
import com.siberanka.vibecore.core.validation.Result;
import com.siberanka.vibecore.project.Identifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.command.CommandSender;

/** {@code /vibecore badge list [search]} and {@code /vibecore badge preview <badge>}. */
final class BadgeCommand {

    private static final int LIST_LIMIT = 20;

    private final VibeCorePlugin plugin;
    private final Messages messages;

    BadgeCommand(VibeCorePlugin plugin, Messages messages) {
        this.plugin = plugin;
        this.messages = messages;
    }

    void execute(CommandSender sender, String[] args) {
        Optional<BadgeModule> module = plugin.modules().running(ModuleId.BADGE, BadgeModule.class);
        if (module.isEmpty()) {
            messages.send(sender, "badge.disabled");
            return;
        }
        String action = args.length == 0 ? "list" : args[0].toLowerCase(Locale.ROOT);
        String locale = messages.localeOf(sender);
        switch (action) {
            case "list" -> {
                List<BadgeCatalog.Badge> found = module.get().catalog().search(args.length > 1 ? args[1] : "");
                messages.send(sender, "badge.list", "count", Integer.toString(found.size()));
                for (BadgeCatalog.Badge badge : found.stream().limit(LIST_LIMIT).toList()) {
                    sender.sendMessage(messages.render(locale, "badge.entry", resolver(module.get(), badge),
                            "id", badge.id().path()));
                }
            }
            case "preview" -> {
                if (args.length != 2) {
                    messages.send(sender, "badge.usage");
                    return;
                }
                Result<Identifier> id = Identifier.parse("badge", args[1].toLowerCase(Locale.ROOT));
                Optional<BadgeCatalog.Badge> badge = id.isAccepted() ? module.get().catalog().badge(id.orThrow())
                        : Optional.empty();
                if (badge.isEmpty()) {
                    messages.send(sender, "badge.unknown", "badge", args[1]);
                    return;
                }
                sender.sendMessage(messages.render(locale, "badge.preview", resolver(module.get(), badge.get()),
                        "id", badge.get().id().path()));
                module.get().copyForms(badge.get()).ifPresentOrElse(
                        forms -> sender.sendMessage(messages.render(locale, "badge.copy",
                                Placeholder.component("forms", forms))),
                        () -> messages.send(sender, "badge.not-built"));
            }
            default -> messages.send(sender, "badge.usage");
        }
    }

    private TagResolver resolver(BadgeModule module, BadgeCatalog.Badge badge) {
        return TagResolver.resolver(
                Placeholder.component("glyph", module.picture(badge).orElse(Component.text("?"))),
                Placeholder.parsed("name", badge.name()),
                Placeholder.parsed("description", badge.description()));
    }

    List<String> complete(String[] args) {
        List<String> matches = new ArrayList<>();
        Optional<BadgeModule> module = plugin.modules().running(ModuleId.BADGE, BadgeModule.class);
        if (module.isEmpty()) {
            return matches;
        }
        String typed = args[args.length - 1].toLowerCase(Locale.ROOT);
        if (args.length == 1) {
            List.of("list", "preview").stream().filter(action -> action.startsWith(typed)).forEach(matches::add);
        } else if (args.length == 2 && args[0].equalsIgnoreCase("preview")) {
            module.get().catalog().badges().keySet().stream().map(Identifier::path)
                    .filter(name -> name.startsWith(typed)).sorted().limit(50).forEach(matches::add);
        }
        return matches;
    }
}
