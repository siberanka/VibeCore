package com.siberanka.vibecore.variable;

import com.siberanka.vibecore.core.config.CoreStateLoader;
import com.siberanka.vibecore.core.validation.Problem;
import com.siberanka.vibecore.core.validation.Result;
import com.siberanka.vibecore.project.Identifier;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Reads the variable declarations of {@code variables.yml}. A missing file declares none. */
public final class VariableFile {

    public static final String NAME = "variables.yml";

    private VariableFile() {
    }

    public static Result<List<VariableDefinition>> load(Path file) {
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            return Result.accepted(List.of());
        }
        List<Problem> problems = new ArrayList<>();
        Map<String, Object> document = CoreStateLoader.readYaml(file, NAME, problems);
        if (document == null) {
            return Result.rejected(problems);
        }
        Object raw = document.get("variables");
        if (!(raw instanceof List<?> list) || document.size() != 1) {
            return Result.rejected(NAME, "format", "expected a single 'variables' list");
        }
        List<VariableDefinition> definitions = new ArrayList<>();
        Set<Identifier> seen = new HashSet<>();
        for (int index = 0; index < list.size(); index++) {
            String path = NAME + ": variables[" + index + "]";
            if (!(list.get(index) instanceof Map<?, ?> section)) {
                problems.add(new Problem(path, "type", "expected a variable section"));
                continue;
            }
            Map<String, Object> copy = new LinkedHashMap<>();
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
        return problems.isEmpty() ? Result.accepted(definitions) : Result.rejected(problems);
    }
}
