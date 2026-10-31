package com.siberanka.vibecore.logic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.siberanka.vibecore.core.validation.Problem;
import com.siberanka.vibecore.project.Identifier;
import com.siberanka.vibecore.variable.VariableContext;
import com.siberanka.vibecore.variable.VariableDefinition;
import com.siberanka.vibecore.variable.VariableStore;
import com.siberanka.vibecore.variable.VariableValue;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.random.RandomGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LogicEngineTest {

    private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final VariableContext CONTEXT = VariableContext.player(ALICE);

    private VariableStore store;
    private LogicEngine engine;

    private static Identifier key(String path) {
        return new Identifier("vibecore", path);
    }

    @BeforeEach
    void setUp() {
        store = VariableStore.create(List.of(
                VariableDefinition.read(Map.of("key", "coins", "type", "number", "scope", "persistent-player",
                        "default", 20, "min", 0, "max", 1000)).orThrow(),
                VariableDefinition.read(Map.of("key", "gems", "type", "number", "scope", "persistent-player",
                        "min", 0, "max", 100)).orThrow(),
                VariableDefinition.read(Map.of("key", "vip", "type", "boolean", "scope", "online-player")).orThrow()),
                16).orThrow();
        engine = new LogicEngine(store, fixedRandom(0.5));
    }

    private static RandomGenerator fixedRandom(double value) {
        return new RandomGenerator() {
            @Override
            public long nextLong() {
                return 0;
            }

            @Override
            public double nextDouble() {
                return value;
            }
        };
    }

    private static List<Action> parse(Object yamlTree) {
        return LogicParser.actions("actions", yamlTree).orThrow();
    }

    private static Map<String, Object> buy(int price) {
        return Map.of("if", Map.of(
                "condition", Map.of("compare", Map.of("left", Map.of("var", "coins"), "op", ">=", "right", price)),
                "then", List.of(
                        Map.of("add", Map.of("var", "coins", "amount", -price)),
                        Map.of("add", Map.of("var", "gems", "amount", 1)),
                        Map.of("emit", Map.of("type", "message", "key", "shop.bought"))),
                "else", List.of(Map.of("fail", "not-enough-coins"))));
    }

    private String value(String path) {
        return store.get(key(path), CONTEXT).orThrow().canonical();
    }

    @Test
    void successfulChainCommitsAllWritesAndReturnsEffects() {
        Outcome outcome = engine.run(parse(List.of(buy(15))), CONTEXT, LogicEngine.Limits.DEFAULT);

        Outcome.Committed committed = assertInstanceOf(Outcome.Committed.class, outcome);
        assertEquals(2, committed.writes());
        assertEquals("shop.bought", committed.effects().getFirst().arguments().get("key"));
        assertEquals("5", value("coins"));
        assertEquals("1", value("gems"));
    }

    @Test
    void failedGateChangesNothing() {
        Outcome outcome = engine.run(parse(List.of(buy(25))), CONTEXT, LogicEngine.Limits.DEFAULT);

        assertEquals("not-enough-coins", assertInstanceOf(Outcome.Failed.class, outcome).reason());
        assertEquals("20", value("coins"));
        assertEquals("0", value("gems"));
    }

    @Test
    void laterInvalidWriteRollsBackEarlierOnes() {
        store.set(key("gems"), CONTEXT, VariableValue.number(100)).orThrow();
        Outcome outcome = engine.run(parse(List.of(buy(15))), CONTEXT, LogicEngine.Limits.DEFAULT);

        Outcome.Rejected rejected = assertInstanceOf(Outcome.Rejected.class, outcome);
        assertEquals("range", rejected.problems().getFirst().code());
        assertEquals("20", value("coins"), "the coin withdrawal must not survive the failed gem grant");
    }

    @Test
    void readsSeeStagedWrites() {
        List<Action> chain = parse(List.of(
                Map.of("set", Map.of("var", "coins", "value", 3)),
                Map.of("if", Map.of(
                        "condition", Map.of("compare", Map.of("left", Map.of("var", "coins"), "op", "==", "right", 3)),
                        "then", List.of(Map.of("toggle", "vip"))))));
        assertInstanceOf(Outcome.Committed.class, engine.run(chain, CONTEXT, LogicEngine.Limits.DEFAULT));
        assertEquals("true", value("vip"));
    }

    @Test
    void stepAndDepthBudgetsAreEnforced() {
        List<Object> many = new ArrayList<>();
        for (int index = 0; index < 20; index++) {
            many.add(Map.of("toggle", "vip"));
        }
        Outcome steps = engine.run(parse(many), CONTEXT, new LogicEngine.Limits(10, 4));
        assertEquals("step-limit", assertInstanceOf(Outcome.Rejected.class, steps).problems().getFirst().code());
        assertEquals("false", value("vip"));

        Object nested = Map.of("toggle", "vip");
        for (int level = 0; level < 6; level++) {
            nested = Map.of("if", Map.of("condition", Map.of("chance", 1), "then", List.of(nested)));
        }
        Outcome depth = engine.run(parse(List.of(nested)), CONTEXT, new LogicEngine.Limits(100, 4));
        assertEquals("depth-limit", assertInstanceOf(Outcome.Rejected.class, depth).problems().getFirst().code());
    }

    @Test
    void comparisonsAreTypeChecked() {
        var condition = LogicParser.condition("c", Map.of("compare",
                Map.of("left", Map.of("var", "vip"), "op", ">", "right", 1))).orThrow();
        var result = engine.test(condition, CONTEXT, LogicEngine.Limits.DEFAULT);
        assertFalse(result.isAccepted());
        assertEquals("type", result.problems().getFirst().code());

        var any = LogicParser.condition("c", Map.of("any", List.of(
                Map.of("chance", 0.4),
                Map.of("not", Map.of("compare", Map.of("left", Map.of("var", "vip"), "op", "==", "right", true))))))
                .orThrow();
        assertTrue(engine.test(any, CONTEXT, LogicEngine.Limits.DEFAULT).orThrow());
    }

    @Test
    void parserReportsEveryDefectWithItsPath() {
        var result = LogicParser.actions("menu.click", List.of(
                Map.of("launch", "rocket"),
                Map.of("add", Map.of("var", "coins", "amount", "ten")),
                Map.of("set", Map.of("var", "../x", "value", 1)),
                Map.of("if", Map.of("condition", Map.of("compare", Map.of("left", 1, "op", "=>", "right", 2)))),
                Map.of("emit", Map.of("type", "message", "nested", Map.of()))));
        List<String> paths = result.problems().stream().map(Problem::path).toList();
        assertTrue(paths.containsAll(List.of("menu.click[0].launch", "menu.click[1].add.amount",
                "menu.click[2].set.var", "menu.click[3].if.condition.compare.op", "menu.click[4].emit.nested")),
                paths.toString());
    }

    @Test
    void parserBoundsHostileNesting() {
        Object nested = Map.of("chance", 1);
        for (int level = 0; level < 40; level++) {
            nested = Map.of("not", nested);
        }
        var result = LogicParser.condition("c", nested);
        assertEquals("depth-limit", result.problems().getFirst().code());
    }
}
