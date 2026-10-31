package com.siberanka.vibecore.integration.economy;

import java.math.BigDecimal;
import org.bukkit.OfflinePlayer;

/**
 * Money operations of the server's economy. Implementations report the
 * provider's real result; a withdrawal or deposit that the provider refuses
 * returns false and must not have changed the balance.
 */
public interface EconomyProvider {

    String name();

    BigDecimal balance(OfflinePlayer player);

    boolean withdraw(OfflinePlayer player, BigDecimal amount);

    boolean deposit(OfflinePlayer player, BigDecimal amount);
}
