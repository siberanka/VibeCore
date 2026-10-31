package com.siberanka.vibecore.menu;

import com.siberanka.vibecore.core.config.CoreStateLoader;
import com.siberanka.vibecore.core.validation.Problem;
import com.siberanka.vibecore.core.validation.Result;
import com.siberanka.vibecore.logic.Action;
import com.siberanka.vibecore.logic.Condition;
import com.siberanka.vibecore.logic.Operand;
import com.siberanka.vibecore.project.Identifier;
import com.siberanka.vibecore.variable.VariableDefinition;
import java.io.IOException;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.LinkOption;
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
import java.util.stream.Stream;

/**
 * Every menu and variable definition of the data folder, validated together:
 * a menu that refers to an unknown variable or opens an unknown menu rejects
 * the whole candidate.
 *
 * <p>Menus live in {@code menus/}; the file {@code menus/shop/main.yml} becomes
 * {@code vibecore:shop/main}. Variables are declared in {@code variables.yml}.
 */
public record MenuCatalog(Map<Identifier, MenuDefinition> menus, List<VariableDefinition> variables) {

    public static final int MAX_MENU_FILES = 256;

    public MenuCatalog {
        menus = Map.copyOf(menus);
        variables = List.copyOf(variables);
    }

    public Optional<MenuDefinition> menu(Identifier id) {
        return Optional.ofNullable(menus.get(id));
    }

    public static Result<MenuCatalog> load(Path dataFolder, Predicate<String> materialExists,
                                           Set<String> allowedCommands) {
        List<Problem> problems = new ArrayList<>();
        List<VariableDefinition> variables = loadVariables(dataFolder.resolve("variables.yml"), problems);
        TreeMap<Identifier, MenuDefinition> menus = new TreeMap<>();
        Path root = dataFolder.resolve("menus");
        if (Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            for (Path file : menuFiles(root, problems)) {
                String relative = root.relativize(file).toString().replace('\\', '/');
                String label = "menus/" + relative;
                Result<Identifier> id = Identifier.parse(label,
                        relative.substring(0, relative.length() - ".yml".length()));
                if (!id.isAccepted()) {
                    problems.add(new Problem(label, "file-name",
                            "menu file names must be lower-case [a-z0-9_.-] segments"));
                    continue;
                }
                Map<String, Object> document = CoreStateLoader.readYaml(file, label, problems);
                if (document == null) {
                    continue;
                }
                Result<MenuDefinition> menu = MenuParser.parse(id.orThrow(), document, materialExists);
                for (Problem problem : menu.problems()) {
                    problems.add(new Problem(label + ": " + problem.path(), problem.code(), problem.detail()));
                }
                if (menu.isAccepted()) {
                    menus.put(id.orThrow(), menu.orThrow());
                }
            }
        }
        Map<Identifier, VariableDefinition> declared = new java.util.HashMap<>();
        variables.forEach(variable -> declared.put(variable.key(), variable));
        for (MenuDefinition menu : menus.values()) {
            checkReferences(menu, declared, menus.keySet(), materialExists, allowedCommands, problems);
        }
        if (!problems.isEmpty()) {
            return Result.rejected(problems);
        }
        return Result.accepted(new MenuCatalog(menus, variables));
    }

    private static List<Path> menuFiles(Path root, List<Problem> problems) {
        try (Stream<Path> walk = Files.walk(root, 4, new FileVisitOption[0])) {
            List<Path> files = walk.filter(path -> path.getFileName().toString().endsWith(".yml"))
                    .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .sorted().limit(MAX_MENU_FILES + 1L).toList();
            if (files.size() > MAX_MENU_FILES) {
                problems.add(new Problem("menus", "size-limit", "more than " + MAX_MENU_FILES + " menu files"));
                return List.of();
            }
            return files;
        } catch (IOException | RuntimeException failure) {
            problems.add(new Problem("menus", "unreadable", "menu folder could not be listed"));
            return List.of();
        }
    }

    private static List<VariableDefinition> loadVariables(Path file, List<Problem> problems) {
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            return List.of();
        }
        Map<String, Object> document = CoreStateLoader.readYaml(file, "variables.yml", problems);
        if (document == null) {
            return List.of();
        }
        Object raw = document.get("variables");
        if (!(raw instanceof List<?> list) || document.size() != 1) {
            problems.add(new Problem("variables.yml", "format", "expected a single 'variables' list"));
            return List.of();
        }
        List<VariableDefinition> definitions = new ArrayList<>();
        Set<Identifier> seen = new HashSet<>();
        for (int index = 0; index < list.size(); index++) {
            String path = "variables.yml: variables[" + index + "]";
            if (!(list.get(index) instanceof Map<?, ?> section)) {
                problems.add(new Problem(path, "type", "expected a variable section"));
                continue;
            }
            Map<String, Object> copy = new java.util.LinkedHashMap<>();
            section.forEach((key, value) -> copy.put(String.valueOf(key), value));
            Result<VariableDefinition> definition = VariableDefinition.read(copy);
            for (Problem problem : definition.problems()) {
                problems.add(new Problem(path + "." + problem.path(), problem.code(), problem.detail()));
            }
            if (definition.isAccepted()) {
                if (!seen.add(definition.orThrow().key())) {
                    problems.add(new Problem(path, "duplicate", "variable declared twice"));
                } else {
                    definitions.add(definition.orThrow());
                }
            }
        }
        return definitions;
    }

    /** Identifiers and keys a menu refers to. */
    private record Refs(Set<Identifier> variables, Set<Identifier> menus, Set<String> materials,
                        List<CommandTemplate> commands) {
        Refs() {
            this(new HashSet<>(), new HashSet<>(), new HashSet<>(), new ArrayList<>());
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
