package com.siberanka.vibecore.variable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.siberanka.vibecore.project.Identifier;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class VariableStoreTest {

    private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID BOB = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final UUID SCREEN = UUID.fromString("00000000-0000-0000-0000-0000000000ff");

    private static Identifier key(String path) {
        return new Identifier("vibecore", path);
    }

    private static VariableDefinition define(Map<String, Object> section) {
        return VariableDefinition.read(section).orThrow();
    }

    private static List<VariableDefinition> definitions() {
        return List.of(
                define(Map.of("key", "coins", "type", "number", "scope", "persistent-player", "min", 0, "max", 100)),
                define(Map.of("key", "event/open", "type", "boolean", "scope", "persistent-global")),
                define(Map.of("key", "tab", "type", "number", "scope", "session", "min", 0, "max", 5)),
                define(Map.of("key", "afk", "type", "boolean", "scope", "online-player")),
                define(Map.of("key", "nick", "type", "text", "scope", "persistent-player", "max-length", 16)));
    }

    private static VariableStore store() {
        return VariableStore.create(definitions(), 8).orThrow();
    }

    @Test
    void playersAreIsolated() {
        VariableStore store = store();
        store.set(key("coins"), VariableContext.player(ALICE), VariableValue.number(40)).orThrow();
        assertEquals("40", store.get(key("coins"), VariableContext.player(ALICE)).orThrow().canonical());
        assertEquals("0", store.get(key("coins"), VariableContext.player(BOB)).orThrow().canonical());
    }

    @Test
    void scopeRequirementsAreEnforced() {
        VariableStore store = store();
        assertEquals("subject", store.get(key("coins"), VariableContext.GLOBAL).problems().getFirst().code());
        assertEquals("session", store.get(key("tab"), VariableContext.player(ALICE)).problems().getFirst().code());
        assertEquals("unknown-variable", store.get(key("missing"), VariableContext.GLOBAL).problems().getFirst().code());
    }

    @Test
    void writesAreRejectedNotClamped() {
        VariableStore store = store();
        VariableContext alice = VariableContext.player(ALICE);
        store.set(key("coins"), alice, VariableValue.number(95)).orThrow();
        assertEquals("range", store.add(key("coins"), alice, 10).problems().getFirst().code());
        assertEquals("95", store.get(key("coins"), alice).orThrow().canonical());
        assertEquals("type", store.toggle(key("coins"), alice).problems().getFirst().code());
        assertFalse(store.add(key("coins"), alice, Double.NaN).isAccepted());
    }

    @Test
    void lifecycleClearsOnlyTheRightScopes() {
        VariableStore store = store();
        VariableContext session = VariableContext.session(ALICE, SCREEN);
        store.set(key("tab"), session, VariableValue.number(3)).orThrow();
        store.toggle(key("afk"), VariableContext.player(ALICE)).orThrow();
        store.set(key("coins"), VariableContext.player(ALICE), VariableValue.number(7)).orThrow();

        store.closeSession(SCREEN);
        assertEquals("0", store.get(key("tab"), session).orThrow().canonical());
        assertEquals("true", store.get(key("afk"), VariableContext.player(ALICE)).orThrow().canonical());

        store.disconnect(ALICE);
        assertEquals("false", store.get(key("afk"), VariableContext.player(ALICE)).orThrow().canonical());
        assertEquals("7", store.get(key("coins"), VariableContext.player(ALICE)).orThrow().canonical());
    }

    @Test
    void perPlayerLimitIsEnforced() {
        VariableStore store = VariableStore.create(definitions(), 1).orThrow();
        VariableContext alice = VariableContext.player(ALICE);
        store.set(key("coins"), alice, VariableValue.number(1)).orThrow();
        assertEquals("limit", store.set(key("nick"), alice, VariableValue.text("A")).problems().getFirst().code());
        store.set(key("coins"), alice, VariableValue.number(2)).orThrow();
        store.reset(key("coins"), alice).orThrow();
        assertTrue(store.set(key("nick"), alice, VariableValue.text("A")).isAccepted());
    }

    @Test
    void persistentRoundTripKeepsTurkishTextAndOrphans() {
        VariableStore store = store();
        store.set(key("nick"), VariableContext.player(ALICE), VariableValue.text("Şık İğne ç")).orThrow();
        store.set(key("event/open"), VariableContext.GLOBAL, VariableValue.bool(true)).orThrow();
        store.set(key("afk"), VariableContext.player(ALICE), VariableValue.bool(true)).orThrow();
        assertTrue(store.persistentDirty());
        String saved = store.serializePersistent() + "player " + BOB + " vibecore:removed number 5\n";
        assertFalse(store.persistentDirty());
        assertFalse(saved.contains("afk"), "online-player values are never written to disk");

        VariableStore restored = store();
        assertEquals(2, restored.loadPersistent(saved).orThrow());
        assertEquals("Şık İğne ç", restored.get(key("nick"), VariableContext.player(ALICE)).orThrow().canonical());
        assertEquals(1, restored.orphanCount());
        assertTrue(restored.serializePersistent().contains("vibecore:removed number 5"),
                "values of removed definitions are preserved");
    }

    @Test
    void malformedFileIsRejectedWithoutPartialLoad() {
        VariableStore store = store();
        String text = "vibecore-variables 1\nplayer " + ALICE + " vibecore:coins number 5\nplayer nope x y z\n";
        assertFalse(store.loadPersistent(text).isAccepted());
        assertEquals("0", store.get(key("coins"), VariableContext.player(ALICE)).orThrow().canonical());
        assertFalse(store.loadPersistent("vibecore-variables 2\n").isAccepted());
    }

    @Test
    void batchAppliesAllOrNothing() {
        VariableStore store = VariableStore.create(definitions(), 1).orThrow();
        VariableContext alice = VariableContext.player(ALICE);
        var overLimit = store.applyAll(List.of(
                new VariableStore.Write(key("coins"), alice, VariableValue.number(5)),
                new VariableStore.Write(key("nick"), alice, VariableValue.text("A"))));
        assertEquals("limit", overLimit.problems().getFirst().code());
        assertEquals("0", store.get(key("coins"), alice).orThrow().canonical());

        var invalid = store.applyAll(List.of(
                new VariableStore.Write(key("coins"), alice, VariableValue.number(5)),
                new VariableStore.Write(key("tab"), alice, VariableValue.number(1))));
        assertFalse(invalid.isAccepted(), "a session write without a session rejects the whole batch");
        assertEquals("0", store.get(key("coins"), alice).orThrow().canonical());

        assertEquals(1, store.applyAll(List.of(new VariableStore.Write(key("coins"), alice,
                VariableValue.number(5)))).orThrow());
        assertEquals("5", store.get(key("coins"), alice).orThrow().canonical());
    }

    @Test
    void duplicateDefinitionsAreRejected() {
        var definition = definitions().getFirst();
        assertFalse(VariableStore.create(List.of(definition, definition), 8).isAccepted());
    }
}
