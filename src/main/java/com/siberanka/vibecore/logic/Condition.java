package com.siberanka.vibecore.logic;

import java.util.List;
import java.util.Objects;

/** Side-effect free check evaluated against the current variable state. */
public sealed interface Condition permits Condition.Compare, Condition.All, Condition.Any, Condition.Not,
        Condition.Chance {

    enum Comparison {
        EQUAL("=="), NOT_EQUAL("!="), LESS("<"), LESS_OR_EQUAL("<="), GREATER(">"), GREATER_OR_EQUAL(">=");

        private final String symbol;

        Comparison(String symbol) {
            this.symbol = symbol;
        }

        public String symbol() {
            return symbol;
        }

        public boolean ordering() {
            return this != EQUAL && this != NOT_EQUAL;
        }

        public static Comparison fromSymbol(String symbol) {
            for (Comparison comparison : values()) {
                if (comparison.symbol.equals(symbol)) {
                    return comparison;
                }
            }
            return null;
        }
    }

    record Compare(Operand left, Comparison comparison, Operand right) implements Condition {
        public Compare {
            Objects.requireNonNull(left, "left");
            Objects.requireNonNull(comparison, "comparison");
            Objects.requireNonNull(right, "right");
        }
    }

    /** True when every child is true; an empty list is true. */
    record All(List<Condition> conditions) implements Condition {
        public All {
            conditions = List.copyOf(conditions);
        }
    }

    /** True when at least one child is true; an empty list is false. */
    record Any(List<Condition> conditions) implements Condition {
        public Any {
            conditions = List.copyOf(conditions);
        }
    }

    record Not(Condition condition) implements Condition {
        public Not {
            Objects.requireNonNull(condition, "condition");
        }
    }

    /** True with the given probability between 0 and 1. */
    record Chance(double probability) implements Condition {
        public Chance {
            if (!(probability >= 0 && probability <= 1)) {
                throw new IllegalArgumentException("probability must be between 0 and 1");
            }
        }
    }
}
