package com.siberanka.vibecore.integration.economy;

import java.util.Optional;
import org.bukkit.Server;
import org.bukkit.plugin.Plugin;

/**
 * Finds the active economy provider. Resolved on every use, so a provider
 * that registers or unregisters later is picked up, and a server without an
 * economy plugin simply reports none.
 */
public final class Economies {

    private Economies() {
    }

    public static Optional<EconomyProvider> find(Server server) {
        Plugin vault = server.getPluginManager().getPlugin("Vault");
        if (vault == null || !vault.isEnabled()) {
            return Optional.empty();
        }
        try {
            return VaultEconomy.find(server);
        } catch (LinkageError incompatible) {
            return Optional.empty();
        }
    }
}
