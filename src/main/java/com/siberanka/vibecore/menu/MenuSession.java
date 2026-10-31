package com.siberanka.vibecore.menu;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

/**
 * One open menu for one viewer. The session is the inventory's holder, so a
 * click is matched to its session by object identity, never by title or
 * slot number, and a stale inventory from an older session is ignored.
 */
public final class MenuSession implements InventoryHolder {

    private final UUID id = UUID.randomUUID();
    private final UUID viewer;
    private final MenuDefinition menu;
    private final Inventory inventory;
    private final Map<Integer, MenuItem> items;
    private long lastClickNanos = Long.MIN_VALUE;
    private boolean busy;
    private boolean closed;

    MenuSession(UUID viewer, MenuDefinition menu, Component title) {
        this.viewer = Objects.requireNonNull(viewer, "viewer");
        this.menu = Objects.requireNonNull(menu, "menu");
        this.inventory = Bukkit.createInventory(this, menu.size(), title);
        this.items = menu.bySlot();
    }

    public UUID id() {
        return id;
    }

    public UUID viewer() {
        return viewer;
    }

    public MenuDefinition menu() {
        return menu;
    }

    /** The item shown in a slot of this menu, if any. */
    MenuItem item(int slot) {
        return items.get(slot);
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    /**
     * Admits a click when the session is open, no earlier click is still being
     * handled and the debounce interval has passed. Uses the monotonic clock,
     * so wall-clock changes cannot reopen the window.
     */
    boolean tryBeginClick(long nowNanos, long debounceNanos) {
        if (closed || busy || (lastClickNanos != Long.MIN_VALUE && nowNanos - lastClickNanos < debounceNanos)) {
            return false;
        }
        busy = true;
        lastClickNanos = nowNanos;
        return true;
    }

    void endClick() {
        busy = false;
    }

    /** Marks the session closed; returns false when it was already closed. */
    boolean close() {
        if (closed) {
            return false;
        }
        closed = true;
        return true;
    }

    boolean isClosed() {
        return closed;
    }
}
