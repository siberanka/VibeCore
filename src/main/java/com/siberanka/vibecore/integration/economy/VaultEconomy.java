package com.siberanka.vibecore.integration.economy;

import java.math.BigDecimal;
import java.util.Optional;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.OfflinePlayer;
import org.bukkit.Server;
import org.bukkit.plugin.RegisteredServiceProvider;

/** Economy through the Vault API. Loaded only when Vault is installed. */
final class VaultEconomy implements EconomyProvider {

    private final Economy economy;

    private VaultEconomy(Economy economy) {
        this.economy = economy;
    }

    static Optional<EconomyProvider> find(Server server) {
        RegisteredServiceProvider<Economy> registration = server.getServicesManager().getRegistration(Economy.class);
        if (registration == null || registration.getProvider() == null || !registration.getProvider().isEnabled()) {
            return Optional.empty();
        }
        return Optional.of(new VaultEconomy(registration.getProvider()));
    }

    @Override
    public String name() {
        return "Vault/" + economy.getName();
    }

    @Override
    public BigDecimal balance(OfflinePlayer player) {
        double balance = economy.getBalance(player);
        return Double.isFinite(balance) ? BigDecimal.valueOf(balance) : BigDecimal.ZERO;
    }

    @Override
    public boolean withdraw(OfflinePlayer player, BigDecimal amount) {
        EconomyResponse response = economy.withdrawPlayer(player, amount.doubleValue());
        return response != null && response.transactionSuccess();
    }

    @Override
    public boolean deposit(OfflinePlayer player, BigDecimal amount) {
        EconomyResponse response = economy.depositPlayer(player, amount.doubleValue());
        return response != null && response.transactionSuccess();
    }
}
