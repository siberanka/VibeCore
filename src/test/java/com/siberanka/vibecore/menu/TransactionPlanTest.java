package com.siberanka.vibecore.menu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.siberanka.vibecore.logic.Action;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TransactionPlanTest {

    private static InventoryModel model(InventoryModel.Slot... slots) {
        List<InventoryModel.Slot> list = new ArrayList<>(List.of(slots));
        return new InventoryModel(list, material -> material.equals("ender_pearl") ? 16 : 64);
    }

    private static InventoryModel.Slot plain(String material, int amount) {
        return new InventoryModel.Slot(material, amount, true);
    }

    private static InventoryModel.Slot custom(String material, int amount) {
        return new InventoryModel.Slot(material, amount, false);
    }

    @Test
    void customItemsAreNeverCountedOrTaken() {
        InventoryModel model = model(custom("diamond", 64), plain("diamond", 2));
        assertEquals(2, model.count("diamond"));
        assertFalse(model.take("diamond", 3), "the named or enchanted diamonds must not be consumed");
        assertEquals(List.of(custom("diamond", 64), plain("diamond", 2)), model.slots());

        assertTrue(model.take("diamond", 2));
        assertEquals(List.of(custom("diamond", 64), InventoryModel.Slot.EMPTY), model.slots());
    }

    @Test
    void takeSpansStacks() {
        InventoryModel model = model(plain("wheat", 5), plain("stone", 1), plain("wheat", 5));
        assertTrue(model.take("wheat", 8));
        assertEquals(2, model.count("wheat"));
        assertEquals(1, model.count("stone"));
    }

    @Test
    void giveFillsPartialStacksFirstAndIsAllOrNothing() {
        InventoryModel model = model(InventoryModel.Slot.EMPTY, plain("ender_pearl", 10), custom("ender_pearl", 1));
        assertTrue(model.give("ender_pearl", 8));
        assertEquals(List.of(plain("ender_pearl", 2), plain("ender_pearl", 16), custom("ender_pearl", 1)),
                model.slots());

        List<InventoryModel.Slot> before = model.slots();
        assertFalse(model.give("ender_pearl", 15), "15 more pearls do not fit");
        assertEquals(before, model.slots());
    }

    @Test
    void takesHappenBeforeGivesSoASaleFreesRoom() {
        InventoryModel model = model(plain("wheat", 64));
        TransactionPlan plan = TransactionPlan.of(List.of(
                new Action.Emit("item-give", Map.of("material", "emerald", "amount", "1")),
                new Action.Emit("item-take", Map.of("material", "wheat", "amount", "64"))));
        assertTrue(plan.applyTo(model).isEmpty());
        assertEquals(List.of(plain("emerald", 1)), model.slots());
    }

    @Test
    void shortfallNamesTheProblem() {
        TransactionPlan buy = TransactionPlan.of(List.of(
                new Action.Emit("item-take", Map.of("material", "gold_ingot", "amount", "3")),
                new Action.Emit("item-give", Map.of("material", "diamond"))));
        var missing = buy.applyTo(model(plain("gold_ingot", 2), InventoryModel.Slot.EMPTY)).orElseThrow();
        assertEquals("gold_ingot", missing.material());
        assertTrue(missing.missing());

        var full = buy.applyTo(model(plain("gold_ingot", 5), plain("stone", 64))).orElseThrow();
        assertEquals("diamond", full.material());
        assertFalse(full.missing());
    }

    @Test
    void moneyIsSummedExactly() {
        TransactionPlan plan = TransactionPlan.of(List.of(
                new Action.Emit("money-take", Map.of("amount", "0.10")),
                new Action.Emit("money-take", Map.of("amount", "0.20")),
                new Action.Emit("message", Map.of("text", "hi"))));
        assertEquals(0, plan.moneyTake().compareTo(new BigDecimal("0.30")));
        assertTrue(plan.touchesMoney());
        assertFalse(plan.touchesItems());
        assertTrue(TransactionPlan.of(List.of(new Action.Emit("message", Map.of("text", "x")))).empty());
    }
}
