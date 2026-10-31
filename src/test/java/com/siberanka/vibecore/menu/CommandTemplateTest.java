package com.siberanka.vibecore.menu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.siberanka.vibecore.core.validation.Result;
import com.siberanka.vibecore.project.Identifier;
import com.siberanka.vibecore.variable.VariableValue;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CommandTemplateTest {

    private static final UUID ID = UUID.fromString("00000000-0000-0000-0000-00000000000a");

    private static Result<VariableValue> values(Identifier key) {
        return switch (key.path()) {
            case "level" -> Result.accepted(VariableValue.number(12));
            case "nick" -> Result.accepted(VariableValue.text("x; op attacker"));
            default -> Result.rejected(key.toString(), "unknown-variable", "no such variable");
        };
    }

    @Test
    void rendersTypedPlaceholders() {
        CommandTemplate template = CommandTemplate.parse("run", "give <player> diamond <var:level>").orThrow();
        assertEquals("give", template.root());
        assertEquals("give Alice diamond 12",
                template.render("Alice", ID, CommandTemplateTest::values).orThrow());
        assertEquals("eco give " + ID + " 5", CommandTemplate.parse("run", "eco give <uuid> 5").orThrow()
                .render("Alice", ID, CommandTemplateTest::values).orThrow());
    }

    @Test
    void textVariablesAndUnsafeNamesAreNeverInserted() {
        CommandTemplate nick = CommandTemplate.parse("run", "msg <player> <var:nick>").orThrow();
        assertEquals("type", nick.render("Alice", ID, CommandTemplateTest::values).problems().getFirst().code());

        CommandTemplate give = CommandTemplate.parse("run", "give <player> stone").orThrow();
        assertEquals("unsafe-name", give.render("bad name", ID, CommandTemplateTest::values)
                .problems().getFirst().code());
        assertTrue(give.render(".BedrockUser", ID, CommandTemplateTest::values).isAccepted());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/give <player> stone", "give <player>\nop attacker", "give <target> stone",
            "give <player:x> stone", "op <player>", "minecraft:op <player>", "stop", "<player> hello",
            " give x", "give < x", ""})
    void rejectsUnsafeTemplates(String text) {
        assertFalse(CommandTemplate.parse("run", text).isAccepted(), text);
    }

    @Test
    void allowListIsEnforced() {
        CommandTemplate template = CommandTemplate.parse("run", "eco give <player> 5").orThrow();
        assertTrue(template.checkAllowed("menu", Set.of("eco")).isEmpty());
        assertEquals("not-allowed", template.checkAllowed("menu", Set.of("give")).orElseThrow().code());
    }
}
