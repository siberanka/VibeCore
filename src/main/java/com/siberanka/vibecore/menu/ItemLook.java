package com.siberanka.vibecore.menu;

import java.util.List;

/**
 * How an item is displayed. In a state override every field is optional;
 * an empty field is inherited from the base look.
 *
 * @param model           item model key ({@code namespace:path}) for the item_model component
 * @param customModelData numeric custom model data, for resource packs that select models by number
 * @param head            {@code viewer}, a player UUID or a validated skin texture value; player_head only
 */
public record ItemLook(String material, Integer amount, String name, List<String> lore, Boolean glow,
                       String model, Integer customModelData, String head) {

    public ItemLook {
        lore = lore == null ? null : List.copyOf(lore);
    }

    /** Returns this look with empty fields filled from {@code base}. */
    public ItemLook over(ItemLook base) {
        return new ItemLook(
                material != null ? material : base.material,
                amount != null ? amount : base.amount,
                name != null ? name : base.name,
                lore != null ? lore : base.lore,
                glow != null ? glow : base.glow,
                model != null ? model : base.model,
                customModelData != null ? customModelData : base.customModelData,
                head != null ? head : base.head);
    }
}
