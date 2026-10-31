package com.siberanka.vibecore.menu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.siberanka.vibecore.core.validation.Problem;
import com.siberanka.vibecore.core.validation.Result;
import com.siberanka.vibecore.project.Identifier;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class MenuParserTest {

    private static final Identifier SHOP = new Identifier("vibecore", "shop");
    private static final Set<String> MATERIALS = Set.of("gold_ingot", "barrier", "diamond");

    private static Map<String, Object> shop() {
        Map<String, Object> coin = new LinkedHashMap<>();
        coin.put("slots", List.of(11, 12));
        coin.put("material", "gold_ingot");
        coin.put("name", "<yellow>Coin");
        coin.put("lore", List.of("<gray>Costs 10"));
        coin.put("states", List.of(Map.of(
                "when", Map.of("compare", Map.of("left", Map.of("var", "coins"), "op", "<", "right", 10)),
                "material", "barrier", "name", "<red>Too expensive")));
        coin.put("actions", Map.of("left", List.of(Map.of("add", Map.of("var", "coins", "amount", -10)))));
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("schema-version", 1);
        document.put("title", "<gold>Shop");
        document.put("rows", 3);
        document.put("permission", "vibecore.menu.shop");
        document.put("items", Map.of("coin", coin, "info", Map.of("slot", 4, "material", "diamond")));
        return document;
    }

    private static Result<MenuDefinition> parse(Map<String, Object> document) {
        return MenuParser.parse(SHOP, document, MATERIALS::contains);
    }

    private static List<String> paths(Result<?> result) {
        return result.problems().stream().map(Problem::path).toList();
    }

    @Test
    void parsesCompleteMenu() {
        MenuDefinition menu = parse(shop()).orThrow();
        assertEquals(27, menu.size());
        assertEquals("vibecore.menu.shop", menu.permissionNode().orElseThrow());
        MenuItem coin = menu.bySlot().get(12);
        assertEquals("coin", coin.key());
        assertEquals(1, coin.actionsFor(ClickChannel.LEFT).size());
        assertTrue(coin.actionsFor(ClickChannel.RIGHT).isEmpty());
        assertEquals("diamond", menu.bySlot().get(4).base().material());
    }

    @Test
    void statesInheritEmptyFieldsFromBase() {
        MenuItem coin = parse(shop()).orThrow().bySlot().get(11);
        ItemLook state = coin.states().getFirst().look();
        assertNull(state.lore());
        ItemLook shown = state.over(coin.base());
        assertEquals("barrier", shown.material());
        assertEquals("<red>Too expensive", shown.name());
        assertEquals(List.of("<gray>Costs 10"), shown.lore());
        assertEquals(1, shown.amount());
    }

    @Test
    void reportsEveryDefectWithItsPath() {
        Map<String, Object> document = shop();
        document.put("rows", 9);
        document.put("permission", "Bad Permission");
        document.put("colour", "red");
        document.put("items", Map.of(
                "a", Map.of("slot", 4, "material", "diamond"),
                "b", Map.of("slot", 4, "material", "dirt_block"),
                "c", Map.of("slots", List.of(99), "material", "diamond"),
                "d", Map.of("slot", 1, "material", "diamond", "amount", 500, "actions", Map.of("drop", List.of())),
                "e", Map.of("slot", 2, "slots", List.of(3), "material", "diamond")));

        Result<MenuDefinition> result = parse(document);

        assertFalse(result.isAccepted());
        assertTrue(paths(result).containsAll(List.of("rows", "permission", "colour", "items.b.material",
                "items.d.amount", "items.d.actions.drop", "items.e")), paths(result).toString());
    }

    @Test
    void slotConflictsAreRejected() {
        Map<String, Object> document = shop();
        document.put("items", new LinkedHashMap<>(Map.of(
                "first", Map.of("slot", 0, "material", "diamond"),
                "second", Map.of("slots", List.of(1, 0), "material", "diamond"))));
        Result<MenuDefinition> result = parse(document);
        assertTrue(result.problems().stream().anyMatch(problem -> problem.code().equals("slot-conflict")));
    }

    @Test
    void outOfRangeSlotDependsOnRows() {
        Map<String, Object> document = shop();
        document.put("rows", 1);
        assertTrue(paths(parse(document)).contains("items.coin.slots"));
    }

    @Test
    void stateNeedsCondition() {
        Map<String, Object> document = shop();
        document.put("items", Map.of("x", Map.of("slot", 0, "material", "diamond",
                "states", List.of(Map.of("material", "barrier")))));
        assertTrue(paths(parse(document)).contains("items.x.states[0].when"));
    }

    @Test
    void futureSchemaIsRejected() {
        Map<String, Object> document = shop();
        document.put("schema-version", 2);
        assertEquals("future-version", parse(document).problems().getFirst().code());
    }
}
