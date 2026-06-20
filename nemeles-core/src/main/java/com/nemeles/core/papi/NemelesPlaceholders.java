package com.nemeles.core.papi;

import com.nemeles.core.api.economy.MoneyType;
import com.nemeles.core.economy.DualEconomyService;
import com.nemeles.core.economy.InterestManager;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.Plugin;

import java.math.BigDecimal;
import java.util.Locale;

/**
 * Expansion de PlaceholderAPI del nucleo. Expone los 4 saldos para scoreboard/TAB:
 *   %nemeles_efectivo%  %nemeles_banco%  %nemeles_sucio%  %nemeles_limpio%   (formateado "$1,234.56")
 *   %nemeles_efectivo_raw% ...                                               (numero plano)
 * Lectura sincrona desde cache (rapida); para jugadores online el saldo siempre esta cargado.
 */
public final class NemelesPlaceholders extends PlaceholderExpansion {

    private final Plugin plugin;
    private final DualEconomyService economy;
    private final InterestManager interest; // puede ser null si el interes esta desactivado

    public NemelesPlaceholders(Plugin plugin, DualEconomyService economy, InterestManager interest) {
        this.plugin = plugin;
        this.economy = economy;
        this.interest = interest;
    }

    @Override public String getIdentifier() { return "nemeles"; }
    @Override public String getAuthor() { return "Nemeles"; }
    @Override public String getVersion() { return plugin.getDescription().getVersion(); }
    @Override public boolean persist() { return true; }
    @Override public boolean canRegister() { return true; }

    @Override
    public String onRequest(OfflinePlayer player, String params) {
        if (player == null) {
            return "";
        }
        String p = params.toLowerCase(Locale.ROOT);
        boolean raw = p.endsWith("_raw");
        if (raw) {
            p = p.substring(0, p.length() - 4);
        }
        // Interes de ahorros: %nemeles_interes% (estimado del proximo pago) / %nemeles_interes_raw% /
        // %nemeles_interes_tasa% ("10%"). Util para la billetera del telefono o el scoreboard.
        if (p.equals("interes")) {
            if (interest == null || !interest.enabled()) {
                return raw ? "0" : "$0.00";
            }
            BigDecimal est = interest.estimateForBank(economy.cachedBalance(player.getUniqueId(), MoneyType.BANCO));
            return raw ? est.toPlainString() : String.format(Locale.US, "$%,.2f", est.doubleValue());
        }
        if (p.equals("interes_tasa")) {
            return (interest == null || !interest.enabled()) ? "" : interest.percent();
        }
        MoneyType type = switch (p) {
            case "efectivo" -> MoneyType.EFECTIVO;
            case "banco" -> MoneyType.BANCO;
            case "sucio" -> MoneyType.SUCIO;
            case "limpio" -> MoneyType.LIMPIO;
            default -> null;
        };
        if (type == null) {
            return null; // placeholder desconocido
        }
        BigDecimal value = economy.cachedBalance(player.getUniqueId(), type);
        if (raw) {
            return value.toPlainString();
        }
        return String.format(Locale.US, "$%,.2f", value.doubleValue());
    }
}
