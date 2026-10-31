package com.siberanka.vibecore.core.text;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Map;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

class MessagesTest {

    private static MessageCatalog catalog() {
        Map<String, String> english = Map.of("prefix", "", "greet", "<green>Hello <name></green>");
        Map<String, String> turkish = Map.of("prefix", "", "greet", "<green>Merhaba <name></green>");
        return MessageCatalog.build(Map.of("en", english, "tr", turkish), Map.of()).orThrow();
    }

    @Test
    void argumentsAreInsertedAsLiteralText() {
        Messages messages = new Messages(MessagesTest::catalog, () -> "en");
        String hostile = "<click:run_command:'/op attacker'>click</click><red>";

        Component rendered = messages.render("en", "greet", "name", hostile);

        assertEquals("Hello " + hostile, PlainTextComponentSerializer.plainText().serialize(rendered));
        assertFalse(rendered.toString().contains("ClickEvent{action=run_command"));
        assertNull(rendered.clickEvent());
    }

    @Test
    void turkishCharactersRoundTrip() {
        Messages messages = new Messages(MessagesTest::catalog, () -> "tr");
        Component rendered = messages.render("tr", "greet", "name", "İıŞşĞğÜüÖöÇç");
        assertEquals("Merhaba İıŞşĞğÜüÖöÇç", PlainTextComponentSerializer.plainText().serialize(rendered));
    }

    @Test
    void resolvesClientLanguageWithFallback() {
        assertEquals("tr", MessageCatalog.resolveLocale("tr-TR", "en"));
        assertEquals("tr", MessageCatalog.resolveLocale("tr_tr", "en"));
        assertEquals("en", MessageCatalog.resolveLocale("de-DE", "en"));
        assertEquals("tr", MessageCatalog.resolveLocale("de-DE", "tr"));
        assertEquals("en", MessageCatalog.resolveLocale(null, "xx"));
    }

    @Test
    void missingTranslationIsRejected() {
        var result = MessageCatalog.build(Map.of("en", Map.of("a", "x", "b", "y"), "tr", Map.of("a", "x")), Map.of());
        assertFalse(result.isAccepted());
        assertEquals("lang/tr.b", result.problems().getFirst().path());
    }
}
