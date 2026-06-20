package com.nemeles.core.command;

import com.nemeles.core.economy.DualEconomyService;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;

/**
 * /cajero [comision] — convierte el dinero LIMPIO MARCADO (rastreable, fruto del lavado) en BANCO sin
 * rastro, cobrando una comision. Limpia el flag de core_marked_money.
 */
public final class CashierCommand implements CommandExecutor {

    private final Plugin plugin;
    private final DualEconomyService economy;

    public CashierCommand(Plugin plugin, DualEconomyService economy) {
        this.plugin = plugin;
        this.economy = economy;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player p)) { sender.sendMessage("Solo jugadores."); return true; }
        BigDecimal fee = defaultFee();
        if (args.length >= 1) {
            BigDecimal f = parse(args[0]);
            if (f == null || f.signum() < 0 || f.compareTo(BigDecimal.ONE) >= 0) {
                p.sendMessage("§cComisión inválida (usa 0 a 0.99).");
                return true;
            }
            fee = f;
        }
        final UUID id = p.getUniqueId();
        final BigDecimal ffee = fee;
        economy.markedTotal(id).thenAccept(total -> run(() -> {
            if (total == null || total.signum() <= 0) {
                p.sendMessage("§7[Cajero] No tienes dinero marcado que limpiar. (El dinero recién lavado queda marcado.)");
                return;
            }
            economy.cashier(id, ffee).thenAccept(res -> run(() -> {
                if (res != null && res.success()) {
                    p.sendMessage("§a[Cajero] Rastro eliminado: tu dinero limpio pasó al §fBANCO§a sin rastro §7(comisión " + pct(ffee) + ").");
                    p.sendMessage("§7Saldo de banco: §f$" + res.newBalance().setScale(2, RoundingMode.HALF_UP).toPlainString());
                } else {
                    p.sendMessage("§c[Cajero] No se pudo procesar: " + (res == null ? "error" : res.errorCode()));
                }
            }));
        }));
        return true;
    }

    private BigDecimal defaultFee() {
        return BigDecimal.valueOf(plugin.getConfig().getDouble("economy.cashier.default-fee", 0.10));
    }

    private static BigDecimal parse(String s) {
        try { return new BigDecimal(s); } catch (NumberFormatException e) { return null; }
    }

    private static String pct(BigDecimal f) {
        return f.multiply(BigDecimal.valueOf(100)).setScale(0, RoundingMode.HALF_UP).toPlainString() + "%";
    }

    private void run(Runnable r) { Bukkit.getScheduler().runTask(plugin, r); }
}
