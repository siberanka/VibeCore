package com.siberanka.vibecore.project;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ProjectModelTest {

    @ParameterizedTest
    @ValueSource(strings = {"Menus/Shop", "vibecore:../x", "a:b:c", "x:", ":y", "a/./b", "space id", "a/", "ünicode"})
    void rejectsUnsafeIdentifiers(String text) {
        assertFalse(Identifier.parse("id", text).isAccepted(), text);
    }

    @Test
    void parsesIdentifiersWithDefaultNamespace() {
        assertEquals(new Identifier("vibecore", "menus/shop"), Identifier.parse("id", "menus/shop").orThrow());
        assertEquals("other:a.b", Identifier.parse("id", "other:a.b").orThrow().toString());
    }

    private static SchemaMigration step(int from, String renamedFrom, String renamedTo) {
        return new SchemaMigration() {
            @Override
            public int fromVersion() {
                return from;
            }

            @Override
            public Map<String, Object> apply(Map<String, Object> document) {
                document.put(renamedTo, document.remove(renamedFrom));
                return document;
            }
        };
    }

    @Test
    void migratesStepByStepWithoutTouchingInput() {
        MigrationChain chain = MigrationChain.of(3, List.of(step(2, "title", "heading"), step(1, "name", "title")));
        Map<String, Object> original = new LinkedHashMap<>(Map.of("schema-version", 1, "name", "Shop"));

        Map<String, Object> migrated = chain.migrate(original).orThrow();

        assertEquals(3, migrated.get("schema-version"));
        assertEquals("Shop", migrated.get("heading"));
        assertEquals(Map.of("schema-version", 1, "name", "Shop"), original);
    }

    @Test
    void rejectsFutureAndMissingVersions() {
        MigrationChain chain = MigrationChain.of(1, List.of());
        assertEquals("future-version", chain.migrate(Map.of("schema-version", 2)).problems().getFirst().code());
        assertEquals("missing", chain.migrate(Map.of("schema-version", "1")).problems().getFirst().code());
    }

    @Test
    void chainMustBeGapFree() {
        assertThrows(IllegalArgumentException.class, () -> MigrationChain.of(3, List.of(step(1, "a", "b"))));
    }

    @Test
    void failingMigrationIsReported() {
        SchemaMigration broken = new SchemaMigration() {
            @Override
            public int fromVersion() {
                return 1;
            }

            @Override
            public Map<String, Object> apply(Map<String, Object> document) throws MigrationException {
                throw new MigrationException("ambiguous layout");
            }
        };
        var result = MigrationChain.of(2, List.of(broken)).migrate(Map.of("schema-version", 1));
        assertEquals("migration-failed", result.problems().getFirst().code());
    }

    @Test
    void readsProjectHeader() {
        ProjectHeader header = ProjectHeader.read(Map.of("id", "menus/shop", "type", "inventory-menu",
                "schema-version", 1, "revision", 4)).orThrow();
        assertEquals(ProjectType.INVENTORY_MENU, header.type());
        assertEquals(4, header.revision());

        var rejected = ProjectHeader.read(Map.of("id", "../x", "type", "spaceship"));
        assertTrue(rejected.problems().size() >= 2);
    }
}
