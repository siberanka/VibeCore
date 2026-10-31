package com.siberanka.vibecore.world;

import com.siberanka.vibecore.logic.Action;
import com.siberanka.vibecore.logic.LogicParser;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Money and item changes requested by one committed chain, summed per kind.
 * Takes are always checked and performed before gives, so the same chain can
 * sell and buy within one consistent snapshot.
 */
public record TransactionPlan(BigDecimal moneyTake, BigDecimal moneyGive,
                              Map<String, Integer> itemTake, Map<String, Integer> itemGive) {

    private static final int MAX_ITEMS_PER_MATERIAL = 2304;

    public TransactionPlan {
        itemTake = Map.copyOf(itemTake);
        itemGive = Map.copyOf(itemGive);
    }

    public static TransactionPlan of(List<Action.Emit> effects) {
        BigDecimal take = BigDecimal.ZERO;
        BigDecimal give = BigDecimal.ZERO;
        Map<String, Integer> itemTake = new TreeMap<>();
        Map<String, Integer> itemGive = new TreeMap<>();
        for (Action.Emit effect : effects) {
            Map<String, String> arguments = effect.arguments();
            switch (effect.type()) {
                case "money-take" -> take = take.add(LogicParser.parseMoney(arguments.get("amount")));
                case "money-give" -> give = give.add(LogicParser.parseMoney(arguments.get("amount")));
                case "item-take" -> itemTake.merge(arguments.get("material"), amount(arguments), TransactionPlan::sum);
                case "item-give" -> itemGive.merge(arguments.get("material"), amount(arguments), TransactionPlan::sum);
                default -> {
                }
            }
        }
        return new TransactionPlan(take, give, itemTake, itemGive);
    }

    private static int amount(Map<String, String> arguments) {
        return Integer.parseInt(arguments.getOrDefault("amount", "1"));
    }

    private static int sum(int a, int b) {
        return Math.min(MAX_ITEMS_PER_MATERIAL * 4, a + b);
    }

    public boolean empty() {
        return moneyTake.signum() == 0 && moneyGive.signum() == 0 && itemTake.isEmpty() && itemGive.isEmpty();
    }

    public boolean touchesMoney() {
        return moneyTake.signum() != 0 || moneyGive.signum() != 0;
    }

    public boolean touchesItems() {
        return !itemTake.isEmpty() || !itemGive.isEmpty();
    }

    /** Why the item part does not fit: a material is missing, or the inventory has no room for it. */
    public record Shortfall(String material, boolean missing) {
    }

    /** Applies the item part to a snapshot; returns the first shortfall, or empty when everything fits. */
    public Optional<Shortfall> applyTo(InventoryModel model) {
        for (Map.Entry<String, Integer> take : itemTake.entrySet()) {
            if (!model.take(take.getKey(), take.getValue())) {
                return Optional.of(new Shortfall(take.getKey(), true));
            }
        }
        for (Map.Entry<String, Integer> give : itemGive.entrySet()) {
            if (!model.give(give.getKey(), give.getValue())) {
                return Optional.of(new Shortfall(give.getKey(), false));
            }
        }
        return Optional.empty();
    }
}
