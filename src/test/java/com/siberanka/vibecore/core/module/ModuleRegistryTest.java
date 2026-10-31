package com.siberanka.vibecore.core.module;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;

class ModuleRegistryTest {

    private final List<String> events = new ArrayList<>();

    private VibeModule module(ModuleId id, boolean failOnStart, String unsupported) {
        return new VibeModule() {
            @Override
            public ModuleId id() {
                return id;
            }

            @Override
            public Optional<String> unsupportedReason() {
                return Optional.ofNullable(unsupported);
            }

            @Override
            public void start() {
                events.add("start " + id.key());
                if (failOnStart) {
                    throw new IllegalStateException("boom");
                }
            }

            @Override
            public void stop() {
                events.add("stop " + id.key());
            }
        };
    }

    private static Logger quietLogger() {
        Logger logger = Logger.getAnonymousLogger();
        logger.setUseParentHandlers(false);
        return logger;
    }

    @Test
    void failureIsIsolatedAndStatesAreHonest() {
        ModuleRegistry registry = new ModuleRegistry(quietLogger());
        registry.register(module(ModuleId.MENU, false, null));
        registry.register(module(ModuleId.HUD, true, null));
        registry.register(module(ModuleId.BEDROCK, false, "bridge plugin not installed"));

        registry.apply(Set.of(ModuleId.MENU, ModuleId.HUD, ModuleId.BEDROCK, ModuleId.EDITOR));

        assertEquals(ModuleState.ENABLED, registry.status(ModuleId.MENU).state());
        assertEquals(ModuleState.FAILED, registry.status(ModuleId.HUD).state());
        assertEquals(ModuleState.UNSUPPORTED, registry.status(ModuleId.BEDROCK).state());
        assertEquals(ModuleState.NOT_IMPLEMENTED, registry.status(ModuleId.EDITOR).state());
        assertEquals(ModuleState.DISABLED, registry.status(ModuleId.PACK).state());
        assertTrue(events.contains("stop hud"), "a failed start is cleaned up");
    }

    @Test
    void stopsInReverseOrder() {
        ModuleRegistry registry = new ModuleRegistry(quietLogger());
        registry.register(module(ModuleId.MENU, false, null));
        registry.register(module(ModuleId.HUD, false, null));
        registry.apply(Set.of(ModuleId.MENU, ModuleId.HUD));
        events.clear();

        registry.stopAll();

        assertEquals(List.of("stop hud", "stop menu"), events);
        assertEquals(ModuleState.DISABLED, registry.status(ModuleId.MENU).state());
    }
}
