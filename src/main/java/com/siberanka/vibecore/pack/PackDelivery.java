package com.siberanka.vibecore.pack;

import java.util.HexFormat;
import java.util.UUID;

/** Helpers for offering a built pack to clients. */
public final class PackDelivery {

    private PackDelivery() {
    }

    /** Stable pack id per content hash, so a client recognises the same pack across offers. */
    public static UUID packId(String sha1) {
        return UUID.nameUUIDFromBytes(("vibecore-java:" + sha1).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    public static byte[] hashBytes(String sha1) {
        return HexFormat.of().parseHex(sha1);
    }
}
