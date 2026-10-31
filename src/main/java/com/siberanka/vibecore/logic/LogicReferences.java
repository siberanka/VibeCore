package com.siberanka.vibecore.logic;

import com.siberanka.vibecore.project.Identifier;
import java.util.Set;

/** Collects what conditions and operands refer to, for load-time validation. */
public final class LogicReferences {

    private LogicReferences() {
    }

    /** Adds variable keys and item materials used by {@code condition}; null is ignored. */
    public static void collect(Condition condition, Set<Identifier> variables, Set<String> materials) {
        if (condition == null) {
            return;
        }
        switch (condition) {
            case Condition.Compare compare -> {
                collect(compare.left(), variables);
                collect(compare.right(), variables);
            }
            case Condition.All all -> all.conditions().forEach(child -> collect(child, variables, materials));
            case Condition.Any any -> any.conditions().forEach(child -> collect(child, variables, materials));
            case Condition.Not not -> collect(not.condition(), variables, materials);
            case Condition.Fact fact -> {
                if (fact.type().equals("item")) {
                    materials.add(fact.arguments().get("material"));
                }
            }
            case Condition.Chance ignored -> {
            }
        }
    }

    public static void collect(Operand operand, Set<Identifier> variables) {
        if (operand instanceof Operand.Variable variable) {
            variables.add(variable.key());
        }
    }
}
