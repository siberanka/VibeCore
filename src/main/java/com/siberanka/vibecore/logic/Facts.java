package com.siberanka.vibecore.logic;

import com.siberanka.vibecore.core.validation.Result;
import com.siberanka.vibecore.variable.VariableType;
import com.siberanka.vibecore.variable.VariableValue;
import java.util.Map;

/**
 * Answers {@link Condition.Fact} checks and built-in {@link Operand.Data}
 * values for one viewer, for example from an economy or the player's state.
 */
@FunctionalInterface
public interface Facts {

    /** Built-in data names and their types. Units: health in half hearts, air in ticks, coordinates in blocks. */
    Map<String, VariableType> DATA = Map.ofEntries(
            Map.entry("name", VariableType.TEXT),
            Map.entry("uuid", VariableType.TEXT),
            Map.entry("world", VariableType.TEXT),
            Map.entry("biome", VariableType.TEXT),
            Map.entry("gamemode", VariableType.TEXT),
            Map.entry("health", VariableType.NUMBER),
            Map.entry("max-health", VariableType.NUMBER),
            Map.entry("food", VariableType.NUMBER),
            Map.entry("saturation", VariableType.NUMBER),
            Map.entry("armor", VariableType.NUMBER),
            Map.entry("air", VariableType.NUMBER),
            Map.entry("level", VariableType.NUMBER),
            Map.entry("exp", VariableType.NUMBER),
            Map.entry("x", VariableType.NUMBER),
            Map.entry("y", VariableType.NUMBER),
            Map.entry("z", VariableType.NUMBER),
            Map.entry("ping", VariableType.NUMBER),
            Map.entry("online", VariableType.NUMBER),
            Map.entry("time", VariableType.NUMBER),
            Map.entry("flying", VariableType.BOOLEAN),
            Map.entry("sneaking", VariableType.BOOLEAN));

    /** Rejects every fact and value; used where no outside world is available. */
    Facts NONE = fact -> Result.rejected("condition", "unsupported-fact", "'" + fact.type() + "' is not available here");

    Result<Boolean> test(Condition.Fact fact);

    /** The value of a built-in data name from {@link #DATA}. */
    default Result<VariableValue> data(String name) {
        return Result.rejected("data", "unsupported-fact", "player data '" + name + "' is not available here");
    }
}
