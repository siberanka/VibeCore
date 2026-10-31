package com.siberanka.vibecore.pack.registry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.siberanka.vibecore.project.Identifier;
import org.junit.jupiter.api.Test;

class StableIdRegistryTest {

    private static Identifier id(String path) {
        return new Identifier("vibecore", path);
    }

    @Test
    void releasedValuesAreNotHandedToOtherAssets() {
        StableIdRegistry registry = StableIdRegistry.glyphs("glyph:badges", 0xE100, 0xE102);
        int vip = registry.allocate(id("badge/vip")).orThrow();
        int mod = registry.allocate(id("badge/mod")).orThrow();
        assertEquals(0xE100, vip);
        assertEquals(0xE101, mod);

        registry.release(id("badge/vip"));
        int admin = registry.allocate(id("badge/admin")).orThrow();
        assertEquals(0xE102, admin, "tombstoned value must not be reused");

        assertEquals(vip, registry.allocate(id("badge/vip")).orThrow(), "re-import restores the same glyph");
    }

    @Test
    void exhaustionIsReportedNotWrapped() {
        StableIdRegistry registry = StableIdRegistry.glyphs("glyph:tiny", 0xE000, 0xE000);
        registry.allocate(id("a")).orThrow();
        registry.release(id("a"));
        assertEquals("exhausted", registry.allocate(id("b")).problems().getFirst().code());

        assertTrue(registry.reclaim(0xE000), "explicit migration frees the value");
        assertEquals(0xE000, registry.allocate(id("b")).orThrow());
    }

    @Test
    void reservedForeignValuesAreSkipped() {
        StableIdRegistry registry = new StableIdRegistry("cmd:items", 1000, 1010);
        registry.reserve(1000).orThrow();
        assertEquals(1001, registry.allocate(id("icon/coin")).orThrow());
        assertFalse(registry.claim(id("icon/gem"), 1000).isAccepted());
        assertFalse(registry.reserve(1001).isAccepted());
    }

    @Test
    void claimRespectsTombstonesOfOtherOwners() {
        StableIdRegistry registry = new StableIdRegistry("cmd:items", 1, 10);
        registry.claim(id("a"), 5).orThrow();
        registry.release(id("a"));
        assertEquals("tombstoned", registry.claim(id("b"), 5).problems().getFirst().code());
        assertEquals(5, registry.claim(id("a"), 5).orThrow());
    }

    @Test
    void glyphRangeStaysInBasicPrivateUseArea() {
        assertThrows(IllegalArgumentException.class, () -> StableIdRegistry.glyphs("glyph:x", 0xDFFF, 0xE010));
        assertThrows(IllegalArgumentException.class, () -> StableIdRegistry.glyphs("glyph:x", 0xF000, 0xF900));
    }

    @Test
    void serializationIsDeterministicAndLossless() {
        StableIdRegistry registry = new StableIdRegistry("cmd:items", 1, 100);
        registry.allocate(id("z")).orThrow();
        registry.allocate(id("a")).orThrow();
        registry.allocate(id("m")).orThrow();
        registry.release(id("a"));
        registry.reserve(50).orThrow();

        String text = registry.serialize();
        StableIdRegistry restored = StableIdRegistry.deserialize(text).orThrow();

        assertEquals(text, restored.serialize());
        assertEquals(2, restored.allocate(id("a")).orThrow());
        assertEquals(4, restored.allocate(id("new")).orThrow());
    }

    @Test
    void corruptRegistryIsRejected() {
        String text = "vibecore-stable-ids 1 cmd:items 1 10\nactive 3 vibecore:a\nactive 3 vibecore:b\n";
        assertFalse(StableIdRegistry.deserialize(text).isAccepted());
        assertFalse(StableIdRegistry.deserialize("vibecore-stable-ids 2 x 1 2\n").isAccepted());
        assertFalse(StableIdRegistry.deserialize("hello").isAccepted());
        assertFalse(StableIdRegistry.deserialize("vibecore-stable-ids 1 x 1 10\nactive 99 vibecore:a\n").isAccepted());
    }
}
