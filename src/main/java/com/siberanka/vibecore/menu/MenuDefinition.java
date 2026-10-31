package com.siberanka.vibecore.menu;

import com.siberanka.vibecore.logic.Action;
import com.siberanka.vibecore.logic.Condition;
import com.siberanka.vibecore.project.Identifier;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Validated inventory menu. */
public record MenuDefinition(
        Identifier id,
        String title,
        int rows,
        String permission,
        Condition openCondition,
        List<Action> onOpen,
        List<Action> onClose,
        List<MenuItem> items) {

    public MenuDefinition {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(title, "title");
        onOpen = List.copyOf(onOpen);
        onClose = List.copyOf(onClose);
        items = List.copyOf(items);
    }

    public int size() {
        return rows * 9;
    }

    /** Slot to item lookup; the parser guarantees each slot has at most one item. */
    public Map<Integer, MenuItem> bySlot() {
        Map<Integer, MenuItem> map = new HashMap<>();
        for (MenuItem item : items) {
            item.slots().forEach(slot -> map.put(slot, item));
        }
        return Map.copyOf(map);
    }

    public Optional<String> permissionNode() {
        return Optional.ofNullable(permission);
    }
}
