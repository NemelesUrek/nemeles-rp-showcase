package com.nemeles.core.economy;

import com.nemeles.core.api.economy.MoneyType;
import com.nemeles.core.api.economy.TransactionResult;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import net.milkbowl.vault.economy.EconomyResponse.ResponseType;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.Plugin;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Puente clasico de Vault sobre la economia dual. Expone el saldo {@link MoneyType} configurado como
 * "legacy" (por defecto EFECTIVO) para que el telefono, EssentialsX y tiendas lo lean via Vault.
 * Vault es sincrono: aqui esperamos (join) la operacion async; a escala de pruebas es despreciable.
 * No soporta bancos (los gestiona el sistema de propiedades/facciones mas adelante).
 */
public final class VaultEconomyBridge implements Economy {

    private final Plugin plugin;
    private final DualEconomyService economy;

    public VaultEconomyBridge(Plugin plugin, DualEconomyService economy) {
        this.plugin = plugin;
        this.economy = economy;
    }

    private MoneyType type() {
        return economy.legacyVaultType();
    }

    @SuppressWarnings("deprecation")
    private UUID uuid(String name) {
        var online = Bukkit.getPlayerExact(name);
        return online != null ? online.getUniqueId() : Bukkit.getOfflinePlayer(name).getUniqueId();
    }

    private double balOf(UUID u) {
        try {
            return economy.balance(u, type()).join().doubleValue();
        } catch (Exception e) {
            return 0.0;
        }
    }

    private EconomyResponse op(UUID u, double amount, boolean deposit) {
        if (amount < 0) {
            return new EconomyResponse(0, balOf(u), ResponseType.FAILURE, "Monto negativo");
        }
        BigDecimal amt = BigDecimal.valueOf(amount);
        try {
            TransactionResult r = (deposit
                    ? economy.deposit(u, type(), amt, "vault")
                    : economy.withdraw(u, type(), amt, "vault")).join();
            if (r.success()) {
                return new EconomyResponse(amount, r.newBalance().doubleValue(), ResponseType.SUCCESS, null);
            }
            return new EconomyResponse(0, balOf(u), ResponseType.FAILURE, r.errorCode());
        } catch (Exception e) {
            return new EconomyResponse(0, balOf(u), ResponseType.FAILURE, e.getMessage());
        }
    }

    // ─── metadatos ───────────────────────────────────────────
    @Override public boolean isEnabled() { return true; }
    @Override public String getName() { return "NemelesCore"; }
    @Override public boolean hasBankSupport() { return false; }
    @Override public int fractionalDigits() { return 2; }
    @Override public String format(double amount) {
        return "$" + BigDecimal.valueOf(amount).setScale(2, RoundingMode.HALF_UP).toPlainString();
    }
    @Override public String currencyNamePlural() { return "dolares"; }
    @Override public String currencyNameSingular() { return "dolar"; }

    // ─── cuentas ─────────────────────────────────────────────
    @Override public boolean hasAccount(String playerName) { return true; }
    @Override public boolean hasAccount(OfflinePlayer player) { return true; }
    @Override public boolean hasAccount(String playerName, String worldName) { return true; }
    @Override public boolean hasAccount(OfflinePlayer player, String worldName) { return true; }
    @Override public boolean createPlayerAccount(String playerName) { return true; }
    @Override public boolean createPlayerAccount(OfflinePlayer player) { return true; }
    @Override public boolean createPlayerAccount(String playerName, String worldName) { return true; }
    @Override public boolean createPlayerAccount(OfflinePlayer player, String worldName) { return true; }

    // ─── saldos ──────────────────────────────────────────────
    @Override public double getBalance(String playerName) { return balOf(uuid(playerName)); }
    @Override public double getBalance(OfflinePlayer player) { return balOf(player.getUniqueId()); }
    @Override public double getBalance(String playerName, String world) { return balOf(uuid(playerName)); }
    @Override public double getBalance(OfflinePlayer player, String world) { return balOf(player.getUniqueId()); }

    @Override public boolean has(String playerName, double amount) { return balOf(uuid(playerName)) >= amount; }
    @Override public boolean has(OfflinePlayer player, double amount) { return balOf(player.getUniqueId()) >= amount; }
    @Override public boolean has(String playerName, String worldName, double amount) { return has(playerName, amount); }
    @Override public boolean has(OfflinePlayer player, String worldName, double amount) { return has(player, amount); }

    // ─── retiros / depositos ─────────────────────────────────
    @Override public EconomyResponse withdrawPlayer(String playerName, double amount) { return op(uuid(playerName), amount, false); }
    @Override public EconomyResponse withdrawPlayer(OfflinePlayer player, double amount) { return op(player.getUniqueId(), amount, false); }
    @Override public EconomyResponse withdrawPlayer(String playerName, String worldName, double amount) { return op(uuid(playerName), amount, false); }
    @Override public EconomyResponse withdrawPlayer(OfflinePlayer player, String worldName, double amount) { return op(player.getUniqueId(), amount, false); }

    @Override public EconomyResponse depositPlayer(String playerName, double amount) { return op(uuid(playerName), amount, true); }
    @Override public EconomyResponse depositPlayer(OfflinePlayer player, double amount) { return op(player.getUniqueId(), amount, true); }
    @Override public EconomyResponse depositPlayer(String playerName, String worldName, double amount) { return op(uuid(playerName), amount, true); }
    @Override public EconomyResponse depositPlayer(OfflinePlayer player, String worldName, double amount) { return op(player.getUniqueId(), amount, true); }

    // ─── bancos (no soportados) ──────────────────────────────
    private EconomyResponse noBank() {
        return new EconomyResponse(0, 0, ResponseType.NOT_IMPLEMENTED, "Bancos no soportados por NemelesCore");
    }
    @Override public EconomyResponse createBank(String name, String player) { return noBank(); }
    @Override public EconomyResponse createBank(String name, OfflinePlayer player) { return noBank(); }
    @Override public EconomyResponse deleteBank(String name) { return noBank(); }
    @Override public EconomyResponse bankBalance(String name) { return noBank(); }
    @Override public EconomyResponse bankHas(String name, double amount) { return noBank(); }
    @Override public EconomyResponse bankWithdraw(String name, double amount) { return noBank(); }
    @Override public EconomyResponse bankDeposit(String name, double amount) { return noBank(); }
    @Override public EconomyResponse isBankOwner(String name, String playerName) { return noBank(); }
    @Override public EconomyResponse isBankOwner(String name, OfflinePlayer player) { return noBank(); }
    @Override public EconomyResponse isBankMember(String name, String playerName) { return noBank(); }
    @Override public EconomyResponse isBankMember(String name, OfflinePlayer player) { return noBank(); }
    @Override public List<String> getBanks() { return Collections.emptyList(); }
}
