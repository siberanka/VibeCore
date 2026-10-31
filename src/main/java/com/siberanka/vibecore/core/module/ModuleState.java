package com.siberanka.vibecore.core.module;

/**
 * Observable module state. Only {@link #ENABLED} means the module is running;
 * every other state is reported honestly instead of claiming success.
 */
public enum ModuleState {
    /** Running in this server. */
    ENABLED,
    /** Implemented but switched off by configuration. */
    DISABLED,
    /** Requested, but this build does not contain an implementation yet. */
    NOT_IMPLEMENTED,
    /** Implemented, but the server lacks a required capability or dependency. */
    UNSUPPORTED,
    /** Start-up was attempted and failed; other modules keep running. */
    FAILED
}
