package com.siberanka.vibecore.menu;

import com.siberanka.vibecore.core.validation.Problem;
import com.siberanka.vibecore.core.validation.Result;
import com.siberanka.vibecore.logic.Action;
import com.siberanka.vibecore.logic.Condition;
import com.siberanka.vibecore.logic.Operand;
import com.siberanka.vibecore.project.DefinitionFiles;
import com.siberanka.vibecore.project.Identifier;
import com.siberanka.vibecore.variable.VariableDefinition;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Predicate;

/**
 * Every menu of the data folder, validated together and against the declared
 * variables: a menu that refers to an unknown variable or opens an unknown menu
 * rejects the whole candidate.
 *
 * <p>Menus live in {@code menus/}; the file {@code menus/shop/main.yml} becomes
 * {@code vibecore:shop/main}. Variables are declared in {@code variables.yml}.
 */
public record MenuCatalog(Map<Identifier, MenuDefinition> menus) {

    public MenuCatalog {
        menus = Map.copyOf(menus);
    }

    public Optional<MenuDefinition> menu(Identifier id) {
        return Optional.ofNullable(menus.get(id));
    }

    public static Result<MenuCatalog> load(Path dataFolder, Predicate<String> materialExists,
                                           Set<String> allowedCommands,
                                           Map<Identifier, VariableDefinition> variables) {
        List<Problem> problems = new ArrayList<>();
        TreeMap<Identifier, MenuDefinition> menus = new TreeMap<>();
        for (var entry : DefinitionFiles.read(dataFolder.resolve("menus"), "menus", problems).entrySet()) {
            Result<MenuDefinition> menu = MenuParser.parse(entry.getKey(), entry.getValue(), materialExists);
            problems.addAll(DefinitionFiles.label("menus", entry.getKey(), menu.problems()));
            if (menu.isAccepted()) {
                menus.put(entry.getKey(), menu.orThrow());
            }
        }
        for (MenuDefinition menu : menus.values()) {
            checkReferences(menu, variables, menus.keySet(), materialExists, allowedCommands, problems);
        }
        if (!problems.isEmpty()) {
            return Result.rejected(problems);
        }
        return Result.accepted(new MenuCatalog(menus));
    }

    /** Identifiers and keys a menu refers to. */
    private record Refs(Set<Identifier> variables, Set<Identifier> menus, Set<String> materials,
                        List<CommandTemplate> commands, Set<Identifier> inputs) {
        Refs() {
            this(new HashSet<>(), new HashSet<>(), new HashSet<>(), new ArrayList<>(), new HashSet<>());
        }
    }

    private static void checkReferences(MenuDefinition menu, Map<Identifier, VariableDefinition> variables,
                                        Set<Identifier> menus, Predicate<String> materialExists,
                                        Set<String> allowedCommands, List<Problem> problems) {
        Refs refs = new Refs();
        collect(menu.openCondition(), refs);
        collect(menu.onOpen(), refs);
        collect(menu.onClose(), refs);
        for (MenuItem item : menu.items()) {
            collect(item.visible(), refs);
            item.states().forEach(state -> collect(state.when(), refs));
            for (ClickChannel channel : EnumSet.allOf(ClickChannel.class)) {
                collect(item.actionsFor(channel), refs);
            }
        }
        String label = "menus/" + menu.id().path() + ".yml";
        refs.variables().stream().filter(key -> !variables.containsKey(key)).sorted().forEach(key ->
                problems.add(new Problem(label, "unknown-variable", key + " is not declared in variables.yml")));
        refs.menus().stream().filter(key -> !menus.contains(key)).sorted().forEach(key ->
                problems.add(new Problem(label, "unknown-menu", "opens " + key + ", which is not a valid menu")));
        refs.materials().stream().filter(key -> !materialExists.test(key)).sorted().forEach(key ->
                problems.add(new Problem(label, "unknown-material", "'" + key + "' is not an item here")));
        for (Identifier key : refs.inputs()) {
            VariableDefinition definition = variables.get(key);
            if (definition == null) {
                problems.add(new Problem(label, "unknown-variable", key + " is not declared in variables.yml"));
            } else if (definition.scope() == com.siberanka.vibecore.variable.VariableScope.SESSION
                    || definition.scope() == com.siberanka.vibecore.variable.VariableScope.PERSISTENT_GLOBAL) {
                problems.add(new Problem(label, "scope", key
                        + " must be an online-player or persistent-player variable to receive chat input"));
            }
        }
        for (CommandTemplate command : refs.commands()) {
            command.checkAllowed(label, allowedCommands).ifPresent(problems::add);
            for (Identifier key : command.variables()) {
                VariableDefinition definition = variables.get(key);
                if (definition == null) {
                    problems.add(new Problem(label, "unknown-variable", key + " is not declared in variables.yml"));
                } else if (definition.type() == com.siberanka.vibecore.variable.VariableType.TEXT) {
                    problems.add(new Problem(label, "type", key + " is text and cannot be inserted into a command"));
                }
            }
        }
    }

    private static void collect(List<Action> chain, Refs refs) {
        for (Action action : chain) {
            switch (action) {
                case Action.SetVariable set -> {
                    refs.variables().add(set.key());
                    collect(set.value(), refs);
                }
                case Action.AddVariable add -> refs.variables().add(add.key());
                case Action.ToggleVariable toggle -> refs.variables().add(toggle.key());
                case Action.ResetVariable reset -> refs.variables().add(reset.key());
                case Action.Cooldown cooldown -> refs.variables().add(cooldown.key());
                case Action.If branch -> {
                    collect(branch.condition(), refs);
                    collect(branch.then(), refs);
                    collect(branch.otherwise(), refs);
                }
                case Action.Emit emit -> {
                    if (emit.type().equals("open")) {
                        Identifier.parse("menu", emit.arguments().get("menu")).map(refs.menus()::add);
                    } else if (emit.type().startsWith("item-")) {
                        refs.materials().add(emit.arguments().getOrDefault("material", ""));
                    } else if (emit.type().equals("input")) {
                        Identifier.parse("var", emit.arguments().get("var")).map(refs.inputs()::add);
                    } else if (emit.type().equals("command")) {
                        CommandTemplate.parse("run", emit.arguments().get("run")).map(refs.commands()::add);
                    }
                }
                case Action.Fail ignored -> {
                }
            }
        }
    }

    private static void collect(Condition condition, Refs refs) {
        if (condition == null) {
            return;
        }
        switch (condition) {
            case Condition.Compare compare -> {
                collect(compare.left(), refs);
                collect(compare.right(), refs);
            }
            case Condition.All all -> all.conditions().forEach(child -> collect(child, refs));
            case Condition.Any any -> any.conditions().forEach(child -> collect(child, refs));
            case Condition.Not not -> collect(not.condition(), refs);
            case Condition.Fact fact -> {
                if (fact.type().equals("item")) {
                    refs.materials().add(fact.arguments().get("material"));
                }
            }
            case Condition.Chance ignored -> {
            }
        }
    }

    private static void collect(Operand operand, Refs refs) {
        if (operand instanceof Operand.Variable variable) {
            refs.variables().add(variable.key());
        }
    }
}
