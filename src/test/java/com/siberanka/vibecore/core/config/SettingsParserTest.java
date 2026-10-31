package com.siberanka.vibecore.core.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.siberanka.vibecore.core.module.ModuleId;
import com.siberanka.vibecore.core.validation.Problem;
import com.siberanka.vibecore.core.validation.Result;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SettingsParserTest {

    private static Map<String, Object> valid() {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("config-version", 1);
        document.put("locale", "tr");
        document.put("modules", Map.of("menu", true, "hud", false));
        document.put("workers", Map.of("threads", 3, "queue-capacity", 32));
        return document;
    }

    private static Set<String> codes(Result<?> result) {
        return Set.copyOf(result.problems().stream().map(Problem::code).toList());
    }

    @Test
    void acceptsCompleteDocument() {
        CoreSettings settings = SettingsParser.parse(valid()).orThrow();
        assertEquals("tr", settings.defaultLocale());
        assertEquals(Set.of(ModuleId.MENU), settings.enabledModules());
        assertEquals(new CoreSettings.WorkerSettings(3, 32), settings.workers());
    }

    @Test
    void reportsEveryProblemInOnePass() {
        Map<String, Object> document = valid();
        document.put("locale", "de");
        document.put("modules", Map.of("menu", "yes", "typo", true));
        document.put("workers", Map.of("threads", 0, "queue-capacity", 1.5));
        document.put("unexpected", 1);

        Result<CoreSettings> result = SettingsParser.parse(document);

        assertFalse(result.isAccepted());
        List<String> paths = result.problems().stream().map(Problem::path).toList();
        assertTrue(paths.containsAll(List.of("locale", "modules.menu", "modules.typo",
                "workers.threads", "workers.queue-capacity", "unexpected")), paths.toString());
    }

    @Test
    void rejectsMissingAndFutureVersions() {
        Map<String, Object> missing = valid();
        missing.remove("config-version");
        assertTrue(codes(SettingsParser.parse(missing)).contains("missing"));

        Map<String, Object> future = valid();
        future.put("config-version", CoreSettings.CURRENT_VERSION + 1);
        assertTrue(codes(SettingsParser.parse(future)).contains("future-version"));
    }

    @Test
    void rejectedCandidateKeepsActiveState() {
        CoreSettings first = SettingsParser.parse(valid()).orThrow();
        ActiveState<CoreSettings> state = new ActiveState<>(first);

        Map<String, Object> broken = valid();
        broken.put("workers", Map.of("threads", 99));
        state.offer(SettingsParser.parse(broken));

        assertEquals(first, state.get());
        assertEquals(1, state.revision().revision());

        Map<String, Object> changed = valid();
        changed.put("locale", "en");
        state.offer(SettingsParser.parse(changed));
        assertEquals("en", state.get().defaultLocale());
        assertEquals(2, state.revision().revision());
    }
}
