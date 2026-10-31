package com.siberanka.vibecore.world;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.ToIntFunction;

/**
 * Snapshot of a player's storage slots used to plan item changes before any
 * real item moves.
 *
 * <p>Only plain stacks count and are taken: a stack with a custom name, lore,
 * enchantments or other data is never consumed by a purchase of the vanilla
 * material. Gives fill matching plain stacks first, then empty slots. Every
 * operation either fits completely or changes nothing.
 */
public final class InventoryModel {

    /** One slot. {@code material} is null for an empty slot. */
    public record Slot(String material, int amount, boolean plain) {
        public static final Slot EMPTY = new Slot(null, 0, true);

        public boolean empty() {
            return material == null || amount <= 0;
        }
    }

    private final Slot[] slots;
    private final ToIntFunction<String> maxStack;

    public InventoryModel(List<Slot> slots, ToIntFunction<String> maxStack) {
        this.slots = slots.stream().map(slot -> slot == null ? Slot.EMPTY : slot).toArray(Slot[]::new);
        this.maxStack = Objects.requireNonNull(maxStack, "maxStack");
    }

    public List<Slot> slots() {
        return List.of(slots);
    }

    public int count(String material) {
        int total = 0;
        for (Slot slot : slots) {
            if (!slot.empty() && slot.plain() && slot.material().equals(material)) {
                total += slot.amount();
            }
        }
        return total;
    }

    /** Removes {@code amount} plain items; returns false and changes nothing when there are not enough. */
    public boolean take(String material, int amount) {
        if (amount < 1 || count(material) < amount) {
            return false;
        }
        int remaining = amount;
        for (int index = slots.length - 1; index >= 0 && remaining > 0; index--) {
            Slot slot = slots[index];
            if (slot.empty() || !slot.plain() || !slot.material().equals(material)) {
                continue;
            }
            int removed = Math.min(remaining, slot.amount());
            remaining -= removed;
            slots[index] = slot.amount() == removed ? Slot.EMPTY : new Slot(material, slot.amount() - removed, true);
        }
        return true;
    }

    /** Adds {@code amount} plain items; returns false and changes nothing when they do not fit. */
    public boolean give(String material, int amount) {
        int stack = maxStack.applyAsInt(material);
        if (amount < 1 || stack < 1) {
            return false;
        }
        Slot[] working = slots.clone();
        int remaining = amount;
        for (int index = 0; index < working.length && remaining > 0; index++) {
            Slot slot = working[index];
            if (!slot.empty() && slot.plain() && slot.material().equals(material) && slot.amount() < stack) {
                int added = Math.min(remaining, stack - slot.amount());
                working[index] = new Slot(material, slot.amount() + added, true);
                remaining -= added;
            }
        }
        for (int index = 0; index < working.length && remaining > 0; index++) {
            if (working[index].empty()) {
                int added = Math.min(remaining, stack);
                working[index] = new Slot(material, added, true);
                remaining -= added;
            }
        }
        if (remaining > 0) {
            return false;
        }
        System.arraycopy(working, 0, slots, 0, slots.length);
        return true;
    }

    /** Indexes of slots that differ from {@code before}. */
    public List<Integer> changedSince(List<Slot> before) {
        List<Integer> changed = new ArrayList<>();
        for (int index = 0; index < slots.length; index++) {
            if (!slots[index].equals(before.get(index) == null ? Slot.EMPTY : before.get(index))) {
                changed.add(index);
            }
        }
        return changed;
    }
}
