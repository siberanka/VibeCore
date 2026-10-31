package com.siberanka.vibecore.logic;

import com.siberanka.vibecore.project.Identifier;
import com.siberanka.vibecore.variable.VariableValue;
import java.util.Objects;

/** Value source of a comparison or assignment. */
public sealed interface Operand permits Operand.Literal, Operand.Variable, Operand.Data {

    /** Built-in viewer data, answered by {@link Facts#data(String)}; see {@link Facts#DATA}. */
    record Data(String name) implements Operand {
        public Data {
            Objects.requireNonNull(name, "name");
        }
    }

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
