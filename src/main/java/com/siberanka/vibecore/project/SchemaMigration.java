package com.siberanka.vibecore.project;

import java.util.Map;

/**
 * Upgrades a document from {@link #fromVersion()} to the next schema version.
 * Implementations receive a private deep copy and return the upgraded tree.
 */
public interface SchemaMigration {

    int fromVersion();

    Map<String, Object> apply(Map<String, Object> document) throws MigrationException;

    /** Raised when a document cannot be upgraded without losing meaning. */
    final class MigrationException extends Exception {
        public MigrationException(String message) {
            super(message);
        }
    }
}
