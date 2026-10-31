package com.siberanka.vibecore.logic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.siberanka.vibecore.core.validation.Problem;
import com.siberanka.vibecore.core.validation.Result;
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

    private static Map<String, Object> purchase() {
        return Map.of("if", Map.of(
                "condition", Map.of("has-money", 5),
                "then", List.of(
                        Map.of("add", Map.of("var", "gems", "amount", 1)),
                        Map.of("emit", Map.of("type", "money-take", "amount", 5))),
                "else", List.of(Map.of("fail", "poor"))));
    }

    @Test
    void factsDecideConditions() {
        Facts rich = fact -> Result.accepted(fact.type().equals("money"));
        Outcome outcome = engine.run(parse(List.of(purchase())), CONTEXT, LogicEngine.Limits.DEFAULT, rich,
                SideEffects.NONE);
        assertInstanceOf(Outcome.Committed.class, outcome);
        assertEquals("1", value("gems"));

        Outcome withoutFacts = engine.run(parse(List.of(purchase())), CONTEXT, LogicEngine.Limits.DEFAULT);
        assertEquals("unsupported-fact",
                assertInstanceOf(Outcome.Rejected.class, withoutFacts).problems().getFirst().code());
    }

    @Test
    void unmetSideEffectFailsWithoutVariableChanges() {
        Facts rich = fact -> Result.accepted(true);
        SideEffects broke = effects -> Result.rejected(
                "transaction", SideEffects.UNMET, "<red>You need 5 more");
        Outcome outcome = engine.run(parse(List.of(purchase())), CONTEXT, LogicEngine.Limits.DEFAULT, rich, broke);
        assertEquals("<red>You need 5 more", assertInstanceOf(Outcome.Failed.class, outcome).reason());
        assertEquals("0", value("gems"));
    }

    @Test
    void failedVariableCommitRevertsSideEffects() {
        List<String> ledger = new ArrayList<>();
        Facts rich = fact -> Result.accepted(true);
        SideEffects recording = effects -> {
            ledger.add("withdraw");
            return Result.accepted(() -> ledger.add("refund"));
        };
        List<Action> chain = parse(List.of(
                Map.of("emit", Map.of("type", "money-take", "amount", 5)),
                Map.of("set", Map.of("var", "gems", "value", 100))));
        // The player already holds the one value allowed, so applying the new gems value fails at commit.
        VariableStore full = VariableStore.create(List.of(VariableDefinition.read(Map.of("key", "gems",
                "type", "number", "scope", "persistent-player", "min", 0, "max", 100)).orThrow(),
                VariableDefinition.read(Map.of("key", "coins", "type", "number", "scope", "persistent-player",
                        "min", 0, "max", 100)).orThrow()), 1).orThrow();
        full.set(key("coins"), CONTEXT, VariableValue.number(1)).orThrow();
        Outcome rejected = new LogicEngine(full, fixedRandom(0.5)).run(chain, CONTEXT, LogicEngine.Limits.DEFAULT,
                rich, recording);
        assertEquals("limit", assertInstanceOf(Outcome.Rejected.class, rejected).problems().getFirst().code());
        assertEquals(List.of("withdraw", "refund"), ledger, "the withdrawal is refunded when variables fail");
    }

    @Test
    void cooldownBlocksUntilItsPeriodPassed() {
        VariableStore claims = VariableStore.create(List.of(VariableDefinition.read(Map.of("key", "daily",
                "type", "number", "scope", "persistent-player", "min", 0, "max", 9_999_999_999L)).orThrow()), 4)
                .orThrow();
        long[] now = {1_000_000};
        LogicEngine timed = new LogicEngine(claims, fixedRandom(0.5), () -> now[0]);
        List<Action> claim = parse(List.of(
                Map.of("cooldown", Map.of("var", "daily", "seconds", 3600, "fail", "<red>Wait <remaining>")),
                Map.of("emit", Map.of("type", "message", "text", "claimed"))));

        assertInstanceOf(Outcome.Committed.class, timed.run(claim, CONTEXT, LogicEngine.Limits.DEFAULT));
        now[0] += 1200;
        Outcome blocked = timed.run(claim, CONTEXT, LogicEngine.Limits.DEFAULT);
        assertEquals("<red>Wait 40m 00s", assertInstanceOf(Outcome.Failed.class, blocked).reason());
        now[0] += 2400;
        assertInstanceOf(Outcome.Committed.class, timed.run(claim, CONTEXT, LogicEngine.Limits.DEFAULT));

        now[0] -= 10 * 3600;
        assertInstanceOf(Outcome.Committed.class, timed.run(claim, CONTEXT, LogicEngine.Limits.DEFAULT),
                "a clock that moved back far does not lock the player out forever");
    }

    @Test
    void durationsAreCompact() {
        assertEquals("9s", LogicEngine.duration(9));
        assertEquals("1m 05s", LogicEngine.duration(65));
        assertEquals("2h 00m 01s", LogicEngine.duration(7201));
        assertEquals("3d 04h 00m", LogicEngine.duration(3 * 86400 + 4 * 3600));
    }

    @Test
    void parsesFactConditions() {
        assertTrue(LogicParser.condition("c", Map.of("has-item", Map.of("material", "diamond", "amount", 3)))
                .isAccepted());
        assertTrue(LogicParser.condition("c", Map.of("has-permission", "shop.vip")).isAccepted());
        assertEquals("range", LogicParser.condition("c", Map.of("has-money", -1)).problems().getFirst().code());
        assertEquals("range", LogicParser.condition("c", Map.of("has-money", "1.005")).problems().getFirst().code());
        assertFalse(LogicParser.condition("c", Map.of("has-item", Map.of("material", "Diamond"))).isAccepted());
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
