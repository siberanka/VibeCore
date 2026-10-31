package com.siberanka.vibecore.logic;

import com.siberanka.vibecore.project.Identifier;
import com.siberanka.vibecore.variable.VariableValue;
import java.util.Objects;

/** Value source of a comparison or assignment. */
public sealed interface Operand permits Operand.Literal, Operand.Variable {

    record Literal(VariableValue value) implements Operand {
        public Literal {
            Objects.requireNonNull(value, "value");
        }
    }

    record Variable(Identifier key) implements Operand {
        public Variable {
            Objects.requireNonNull(key, "key");
        }
    }
}
