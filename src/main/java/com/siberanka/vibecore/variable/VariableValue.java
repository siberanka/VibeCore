package com.siberanka.vibecore.variable;

import java.math.BigDecimal;
import java.util.Objects;

/** Typed variable value. Numbers are always finite. */
public sealed interface VariableValue permits VariableValue.NumberValue, VariableValue.BooleanValue,
        VariableValue.TextValue {

    VariableType type();

    /** Canonical, locale-independent text used for storage and comparisons. */
    String canonical();

    static VariableValue number(double value) {
        return new NumberValue(value);
    }

    static VariableValue bool(boolean value) {
        return value ? BooleanValue.TRUE : BooleanValue.FALSE;
    }

    static VariableValue text(String value) {
        return new TextValue(value);
    }

    record NumberValue(double value) implements VariableValue {
        public NumberValue {
            if (!Double.isFinite(value)) {
                throw new IllegalArgumentException("Number values must be finite");
            }
            if (value == 0.0d) {
                value = 0.0d;
            }
        }

        @Override
        public VariableType type() {
            return VariableType.NUMBER;
        }

        @Override
        public String canonical() {
            return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
        }
    }

    enum BooleanValue implements VariableValue {
        TRUE, FALSE;

        public boolean value() {
            return this == TRUE;
        }

        @Override
        public VariableType type() {
            return VariableType.BOOLEAN;
        }

        @Override
        public String canonical() {
            return value() ? "true" : "false";
        }
    }

    record TextValue(String value) implements VariableValue {
        public TextValue {
            Objects.requireNonNull(value, "value");
        }

        @Override
        public VariableType type() {
            return VariableType.TEXT;
        }

        @Override
        public String canonical() {
            return value;
        }
    }
}
