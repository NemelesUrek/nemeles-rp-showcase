package com.nemeles.core.economy;

import com.nemeles.core.api.economy.MoneyType;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;

/**
 * Interes de los AHORROS (saldo BANCO, el que muestra la billetera del telefono). Programa una tarea
 * periodica que, a los jugadores CONECTADOS, les paga un % del banco cada cierto intervalo (por defecto
 * 24h). Tambien lo intenta al entrar el jugador. La logica atomica vive en
 * {@link DualEconomyService#payInterestIfDue}.
 *
 * <p><b>Anti-inflacion / anti-abuso:</b> el interes se calcula sobre {@code min(banco, principalCap)} y
 * se acota por {@code maxPayout}, de modo que el rendimiento es LINEAL (no exponencial) y tiene un techo
 * diario fijo aunque el jugador tenga cifras enormes. Solo se paga a jugadores conectados y UNA vez por
 * intervalo, asi que estar desconectado no acumula pagos (al volver cobra UN periodo, no N).</p>
 */
public final class InterestManager implements Listener {

    private final Plugin plugin;
    private final DualEconomyService economy;

    private boolean enabled;
    private BigDecimal rate;          // 0.10 = 10%
    private long principalCapCents;
    private long maxPayoutCents;
    private long minBalanceCents;
    private long intervalMillis;
    private long checkPeriodTicks;
    private boolean notify;

    private BukkitTask task;

    public InterestManager(Plugin plugin, DualEconomyService economy) {
        this.plugin = plugin;
        this.economy = economy;
        reload();
    }

    /** Relee la configuracion (economy.interest.*). Convierte los montos de $ a centimos. */
    public void reload() {
        var cfg = plugin.getConfig();
        this.enabled = cfg.getBoolean("economy.interest.enabled", true);
        double r = cfg.getDouble("economy.interest.rate", 0.05);
        this.rate = BigDecimal.valueOf(Math.max(0d, r));
        this.principalCapCents = toCents(cfg.getDouble("economy.interest.principal-cap", 10000));
        this.maxPayoutCents = toCents(cfg.getDouble("economy.interest.max-payout", 1000));
        this.minBalanceCents = toCents(cfg.getDouble("economy.interest.min-balance", 100));
        double hours = cfg.getDouble("economy.interest.interval-hours", 24);
        if (hours <= 0) hours = 24;
        this.intervalMillis = (long) (hours * 3600_000d);
        double checkMin = cfg.getDouble("economy.interest.check-interval-minutes", 10);
        if (checkMin < 1) checkMin = 1;
        this.checkPeriodTicks = (long) (checkMin * 60d * 20d);
        this.notify = cfg.getBoolean("economy.interest.notify", true);
    }

    private static long toCents(double units) {
        return Money.toCents(BigDecimal.valueOf(Math.max(0d, units)));
    }

    public void start() {
        if (!enabled) {
            plugin.getLogger().info("Interes de ahorros DESACTIVADO (economy.interest.enabled=false).");
            return;
        }
        // Avisos de config peligrosa (no abortan, pero evitan hiperinflacion por erratas en el YAML).
        if (rate.compareTo(BigDecimal.ONE) > 0) {
            plugin.getLogger().warning("[INTERES] rate=" + rate.toPlainString()
                    + " es MAYOR que 1 (>100% por intervalo). Revisa economy.interest.rate (0.10 = 10%).");
        }
        if (maxPayoutCents <= 0L) {
            plugin.getLogger().warning("[INTERES] max-payout<=0: NO hay techo duro por pago; el interes solo"
                    + " lo limita principal-cap. Pon economy.interest.max-payout > 0 para un tope absoluto.");
        }
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        this.task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            for (Player p : Bukkit.getOnlinePlayers()) {
                tryPay(p.getUniqueId());
            }
        }, checkPeriodTicks, checkPeriodTicks);
        plugin.getLogger().info("Interes de ahorros ACTIVO: " + percent() + " del banco cada "
                + (intervalMillis / 3600_000L) + "h (tope $" + Money.fromCents(maxPayoutCents).toPlainString()
                + "/pago, sobre los primeros $" + Money.fromCents(principalCapCents).toPlainString() + ").");
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) {
        if (!enabled) return;
        final UUID id = e.getPlayer().getUniqueId();
        // Pequeno retraso para que el ProfileListener precargue la cuenta primero.
        Bukkit.getScheduler().runTaskLater(plugin, () -> tryPay(id), 100L); // ~5s
    }

    private void tryPay(UUID id) {
        economy.payInterestIfDue(id, rate, principalCapCents, maxPayoutCents, minBalanceCents, intervalMillis)
                .thenAccept(res -> {
                    if (res == null || !res.wasPaid() || !notify) return;
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        Player p = Bukkit.getPlayer(id);
                        if (p == null) return;
                        p.sendMessage("§a[Banco] Tus ahorros generaron §f$" + fmt(res.amount())
                                + " §ade interés. §7Saldo: §f$" + fmt(res.newBanco()));
                    });
                })
                .exceptionally(ex -> {
                    plugin.getLogger().warning("[INTERES] Error pagando a " + id + ": " + ex.getMessage());
                    return null;
                });
    }

    // ─── Para PAPI / UI (telefono, scoreboard) ──────────────────────────────

    public boolean enabled() { return enabled; }

    /** Tasa como porcentaje legible, p.ej. "10%". */
    public String percent() {
        return rate.multiply(BigDecimal.valueOf(100)).setScale(0, RoundingMode.HALF_UP).toPlainString() + "%";
    }

    /** Interes (en unidades) que cobraria AHORA un saldo de banco dado, aplicando los topes. */
    public BigDecimal estimateForBank(BigDecimal banco) {
        long cents = banco == null ? 0L : Money.toCents(banco);
        return Money.fromCents(DualEconomyService.interestCents(
                cents, rate, principalCapCents, maxPayoutCents, minBalanceCents));
    }

    private static String fmt(BigDecimal v) {
        return v == null ? "0.00" : v.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }
}
