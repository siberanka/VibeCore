package com.siberanka.vibecore.world;

import com.siberanka.vibecore.core.text.Messages;
import com.siberanka.vibecore.core.validation.Result;
import com.siberanka.vibecore.integration.economy.Economies;
import com.siberanka.vibecore.integration.economy.EconomyProvider;
import com.siberanka.vibecore.logic.Action;
import com.siberanka.vibecore.logic.Condition;
import com.siberanka.vibecore.logic.Facts;
import com.siberanka.vibecore.logic.LogicParser;
import com.siberanka.vibecore.logic.SideEffects;
import com.siberanka.vibecore.variable.VariableValue;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

/**
 * The viewer's money, items and permissions as seen by a menu chain.
 *
 * <p>All checks and changes happen on the server thread within one tick, from
 * one snapshot. Money is withdrawn and items are taken before anything is
 * given; when a later step fails, earlier steps are reverted. A revert that
 * itself fails is never hidden: it is logged as compensation-required and
 * appended to {@code data/transactions.log} with a transaction id.
 */
public final class PlayerWorld implements Facts, SideEffects {

    private final Player player;
    private final Messages messages;
    private final Logger logger;
    private final Path ledger;

    public PlayerWorld(Player player, Messages messages, Logger logger, Path ledger) {
        this.player = player;
        this.messages = messages;
        this.logger = logger;
        this.ledger = ledger;
    }

    private Optional<EconomyProvider> economy() {
        return Economies.find(player.getServer());
    }

    @Override
    public Result<Boolean> test(Condition.Fact fact) {
        return switch (fact.type()) {
            case "money" -> economy()
                    .map(provider -> Result.accepted(provider.balance(player)
                            .compareTo(LogicParser.parseMoney(fact.arguments().get("amount"))) >= 0))
                    .orElseGet(() -> Result.rejected("has-money", "economy-missing", "no economy provider is active"));
            case "item" -> Result.accepted(snapshot(storage()).count(fact.arguments().get("material"))
                    >= Integer.parseInt(fact.arguments().get("amount")));
            case "permission" -> Result.accepted(player.hasPermission(fact.arguments().get("node")));
            default -> Facts.NONE.test(fact);
        };
    }

    @Override
    public Result<VariableValue> data(String name) {
        Location location = player.getLocation();
        VariableValue value = switch (name) {
            case "name" -> VariableValue.text(player.getName());
            case "uuid" -> VariableValue.text(player.getUniqueId().toString());
            case "world" -> VariableValue.text(player.getWorld().getName());
            case "biome" -> VariableValue.text(location.getBlock().getBiome().getKey().getKey());
            case "gamemode" -> VariableValue.text(player.getGameMode().name().toLowerCase(Locale.ROOT));
            case "health" -> VariableValue.number(player.getHealth());
            case "max-health" -> VariableValue.number(attribute(Attribute.MAX_HEALTH));
            case "food" -> VariableValue.number(player.getFoodLevel());
            case "saturation" -> VariableValue.number(player.getSaturation());
            case "armor" -> VariableValue.number(attribute(Attribute.ARMOR));
            case "air" -> VariableValue.number(player.getRemainingAir());
            case "level" -> VariableValue.number(player.getLevel());
            case "exp" -> VariableValue.number(player.getExp());
            case "x" -> VariableValue.number(location.getBlockX());
            case "y" -> VariableValue.number(location.getBlockY());
            case "z" -> VariableValue.number(location.getBlockZ());
            case "ping" -> VariableValue.number(player.getPing());
            case "online" -> VariableValue.number(player.getServer().getOnlinePlayers().size());
            case "time" -> VariableValue.number(player.getWorld().getTime());
            case "flying" -> VariableValue.bool(player.isFlying());
            case "sneaking" -> VariableValue.bool(player.isSneaking());
            default -> null;
        };
        return value == null ? Facts.super.data(name) : Result.accepted(value);
    }

    private double attribute(Attribute attribute) {
        AttributeInstance instance = player.getAttribute(attribute);
        double value = instance == null ? 0 : instance.getValue();
        return Double.isFinite(value) ? value : 0;
    }

    private ItemStack[] storage() {
        ItemStack[] contents = player.getInventory().getStorageContents();
        ItemStack[] copy = new ItemStack[contents.length];
        for (int index = 0; index < contents.length; index++) {
            copy[index] = contents[index] == null ? null : contents[index].clone();
        }
        return copy;
    }

    private static InventoryModel snapshot(ItemStack[] contents) {
        List<InventoryModel.Slot> slots = new ArrayList<>(contents.length);
        for (ItemStack stack : contents) {
            if (stack == null || stack.getType().isAir() || stack.getAmount() <= 0) {
                slots.add(InventoryModel.Slot.EMPTY);
            } else {
                slots.add(new InventoryModel.Slot(stack.getType().getKey().getKey(), stack.getAmount(),
                        !stack.hasItemMeta()));
            }
        }
        return new InventoryModel(slots, key -> {
            Material material = Material.matchMaterial(key);
            return material == null ? 0 : material.getMaxStackSize();
        });
    }

    private Result<Undo> unmet(String key, String amount, String material) {
        String template = messages.template(player, key)
                .replace("<amount>", amount).replace("<material>", material);
        return Result.rejected("transaction", UNMET, template);
    }

    @Override
    public Result<Undo> perform(List<Action.Emit> effects) {
        TransactionPlan plan = TransactionPlan.of(effects);
        if (plan.empty()) {
            return Result.accepted(() -> {
            });
        }
        EconomyProvider provider = null;
        if (plan.touchesMoney()) {
            provider = economy().orElse(null);
            if (provider == null) {
                logger.warning("A menu action needs money but no economy provider is active");
                return unmet("menu.economy-missing", "", "");
            }
            BigDecimal balance = provider.balance(player);
            if (balance.compareTo(plan.moneyTake()) < 0) {
                return unmet("menu.not-enough-money",
                        plan.moneyTake().subtract(balance).stripTrailingZeros().toPlainString(), "");
            }
        }
        ItemStack[] before = storage();
        InventoryModel model = snapshot(before);
        Optional<TransactionPlan.Shortfall> shortfall = plan.applyTo(model);
        if (shortfall.isPresent()) {
            String material = shortfall.get().material();
            return shortfall.get().missing()
                    ? unmet("menu.not-enough-items", Integer.toString(plan.itemTake().get(material)), material)
                    : unmet("menu.inventory-full", "", material);
        }

        String transaction = UUID.randomUUID().toString().substring(0, 8);
        Deque<Step> undo = new ArrayDeque<>();
        EconomyProvider economy = provider;
        if (plan.moneyTake().signum() > 0) {
            if (!economy.withdraw(player, plan.moneyTake())) {
                return unmet("menu.not-enough-money", plan.moneyTake().toPlainString(), "");
            }
            undo.push(new Step("refund " + plan.moneyTake().toPlainString(),
                    () -> economy.deposit(player, plan.moneyTake())));
        }
        if (plan.touchesItems()) {
            List<Integer> changed = model.changedSince(snapshot(before).slots());
            PlayerInventory inventory = player.getInventory();
            List<InventoryModel.Slot> after = model.slots();
            for (int slot : changed) {
                InventoryModel.Slot target = after.get(slot);
                inventory.setItem(slot, target.empty() ? null
                        : new ItemStack(Material.matchMaterial(target.material()), target.amount()));
            }
            undo.push(new Step("restore " + changed.size() + " slot(s)", () -> {
                for (int slot : changed) {
                    inventory.setItem(slot, before[slot] == null ? null : before[slot].clone());
                }
                return true;
            }));
        }
        if (plan.moneyGive().signum() > 0) {
            if (!economy.deposit(player, plan.moneyGive())) {
                revert(transaction, plan, undo);
                return Result.rejected("money-give", "provider-refused", economy.name() + " refused the deposit");
            }
            undo.push(new Step("withdraw " + plan.moneyGive().toPlainString(),
                    () -> economy.withdraw(player, plan.moneyGive())));
        }
        return Result.accepted(() -> revert(transaction, plan, undo));
    }

    /** One revert step; returns false when the revert did not succeed. */
    private record Step(String description, java.util.function.BooleanSupplier action) {
    }

    private void revert(String transaction, TransactionPlan plan, Deque<Step> undo) {
        while (!undo.isEmpty()) {
            Step step = undo.pop();
            boolean reverted;
            try {
                reverted = step.action().getAsBoolean();
            } catch (RuntimeException failure) {
                logger.log(Level.SEVERE, "Revert step failed in transaction " + transaction, failure);
                reverted = false;
            }
            if (!reverted) {
                String record = Instant.now() + " compensation-required tx=" + transaction + " player="
                        + player.getUniqueId() + " step=\"" + step.description() + "\" plan=" + plan + "\n";
                logger.severe("Transaction " + transaction + " could not be reverted (" + step.description()
                        + "); manual correction is required. Recorded in data/transactions.log");
                try {
                    Files.createDirectories(ledger.getParent());
                    Files.writeString(ledger, record, StandardCharsets.UTF_8,
                            StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                } catch (IOException failure) {
                    logger.log(Level.SEVERE, "Could not record transaction " + transaction + ": " + record, failure);
                }
            }
        }
    }
}
