package com.siberanka.vibecore.variable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class VariableDefinitionTest {

    private static VariableDefinition coins() {
        return VariableDefinition.read(Map.of("key", "shop/coins", "type", "number",
                "scope", "persistent-player", "default", 0, "min", 0, "max", 1000)).orThrow();
    }

    @Test
    void scopesAreDistinct() {
        assertFalse(VariableScope.SESSION.persistent());
        assertFalse(VariableScope.ONLINE_PLAYER.persistent());
        assertTrue(VariableScope.PERSISTENT_PLAYER.persistent());
        assertTrue(VariableScope.PERSISTENT_PLAYER.playerBound());
        assertFalse(VariableScope.PERSISTENT_GLOBAL.playerBound());
    }

    @Test
    void enforcesNumericBounds() {
        VariableDefinition coins = coins();
        assertEquals("250", coins.parse("250").orThrow().canonical());
        assertEquals("range", coins.parse("1001").problems().getFirst().code());
        assertEquals("range", coins.parse("-1").problems().getFirst().code());
    }

    @ParameterizedTest
    @ValueSource(strings = {"NaN", "Infinity", "-Infinity", "1e3", "0x10", "5d", "abc", ""})
    void rejectsNonPlainNumbers(String input) {
        assertFalse(coins().parse(input).isAccepted(), input);
    }

    @Test
    void numbersMustBeFinite() {
        assertThrows(IllegalArgumentException.class, () -> VariableValue.number(Double.NaN));
        assertEquals("0", VariableValue.number(-0.0).canonical());
        assertEquals("1.5", VariableValue.number(1.50).canonical());
    }

    @Test
    void textCountsCodePointsAndRejectsControls() {
        VariableDefinition name = VariableDefinition.read(Map.of("key", "nick", "type", "text",
                "scope", "session", "max-length", 3)).orThrow();
        assertTrue(name.parse("Şğü").isAccepted());
        assertTrue(name.parse("😀ab").isAccepted(), "one supplementary character counts once");
        assertEquals("length", name.parse("abcd").problems().getFirst().code());
        assertEquals("control", name.parse("a\nb").problems().getFirst().code());
    }

    @Test
    void typeMismatchIsRejected() {
        assertEquals("type", coins().check(VariableValue.text("5")).problems().getFirst().code());
        var badDefault = VariableDefinition.read(Map.of("key", "x", "type", "boolean", "scope", "session",
                "default", "yes"));
        assertFalse(badDefault.isAccepted());
        var outOfRangeDefault = VariableDefinition.read(Map.of("key", "x", "type", "number",
                "scope", "session", "default", 50, "min", 0, "max", 10));
        assertFalse(outOfRangeDefault.isAccepted());
    }

    @Test
    void unknownScopeAndKeysAreReported() {
        var result = VariableDefinition.read(Map.of("key", "x", "type", "number", "scope", "forever",
                "persist", true));
        assertTrue(result.problems().size() >= 2);
    }
}
