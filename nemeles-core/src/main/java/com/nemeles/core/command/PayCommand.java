package com.nemeles.core.command;

import com.nemeles.core.api.economy.MoneyType;
import com.nemeles.core.api.economy.TransactionResult;
import com.nemeles.core.economy.DualEconomyService;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * /pagar &lt;jugador&gt; &lt;cantidad&gt; — entrega EFECTIVO en mano a otro jugador cercano.
 *
 * <p>Es el pago "de calle" fundamental de un RP GTA/Mafia: pagar al sicario, al dealer, dividir un
 * botin, dar propina o saldar una palabra de honor — todo cara a cara, sin cajero ni banco. La
 * transferencia usa {@link DualEconomyService#transfer} que es ATOMICA y anti-dupe (lockea ambas
 * cuentas en orden de UUID), asi que no hay forma de duplicar dinero ni con doble clic.</p>
 *
 * <p><b>Reglas RP:</b> el receptor debe estar ONLINE y CERCA (radio configurable, mano a mano). No
 * te puedes pagar a ti mismo. Solo mueve EFECTIVO (no banco, no sucio, no limpio): el dinero sucio se
 * pasa por otras vias (negocio, lavado), el banco por transferencia bancaria del telefono.</p>
 *
 * <p><b>Crossplay:</b> solo mensajes de chat planos, sin componentes ni CustomModelData, asi que
 * funciona identico en Java y Bedrock (Geyser) desde el dia uno.</p>
 *
 * <p><b>Anti-abuso:</b> cantidad minima 1; tope opcional por pago; cooldown corto por pagador para
 * evitar spam; los pagos grandes piden una confirmacion ({@code /pagar confirmar}) con vida corta
 * para que un dedazo no vacie la cartera.</p>
 */
public final class PayCommand implements CommandExecutor, TabCompleter {

    private final Plugin plugin;
    private final DualEconomyService economy;

    /** Pago pendiente de confirmacion (solo para montos grandes). */
    private record Pending(UUID target, String targetName, BigDecimal amount, long expiresAt) {}

    private final Map<UUID, Pending> pending = new HashMap<>();
    /** Ultimo pago por pagador (epoch ms) para el cooldown anti-spam. */
    private final Map<UUID, Long> lastPay = new HashMap<>();

    public PayCommand(Plugin plugin, DualEconomyService economy) {
        this.plugin = plugin;
        this.economy = economy;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player p)) { sender.sendMessage("Solo jugadores."); return true; }

        if (args.length == 0) {
            p.sendMessage(ChatColor.GRAY + "Uso: " + ChatColor.WHITE + "/pagar <jugador> <cantidad>"
                    + ChatColor.GRAY + "  (entrega efectivo en mano, de cerca)");
            return true;
        }

        // /pagar confirmar  ·  /pagar cancelar — resuelven un pago grande pendiente.
        String first = args[0].toLowerCase(java.util.Locale.ROOT);
        if (first.equals("confirmar") || first.equals("confirm") || first.equals("si")) {
            confirm(p);
            return true;
        }
        if (first.equals("cancelar") || first.equals("cancel") || first.equals("no")) {
            if (pending.remove(p.getUniqueId()) != null) {
                p.sendMessage(ChatColor.YELLOW + "[Pago] Cancelado.");
            } else {
                p.sendMessage(ChatColor.GRAY + "[Pago] No tienes ningun pago pendiente.");
            }
            return true;
        }

        if (args.length < 2) {
            p.sendMessage(ChatColor.GRAY + "Uso: " + ChatColor.WHITE + "/pagar <jugador> <cantidad>");
            return true;
        }

        BigDecimal amount = parse(args[1]);
        if (amount == null || amount.signum() <= 0) {
            p.sendMessage(ChatColor.RED + "[Pago] Cantidad invalida.");
            return true;
        }
        amount = amount.setScale(2, RoundingMode.HALF_UP);
        BigDecimal min = BigDecimal.ONE;
        if (amount.compareTo(min) < 0) {
            p.sendMessage(ChatColor.RED + "[Pago] El minimo es $1.");
            return true;
        }
        BigDecimal max = maxPerPay();
        if (max != null && amount.compareTo(max) > 0) {
            p.sendMessage(ChatColor.RED + "[Pago] El maximo por pago es $" + plain(max) + ".");
            return true;
        }

        Player target = Bukkit.getPlayerExact(args[0]);
        String reject = validateTarget(p, target);
        if (reject != null) {
            p.sendMessage(ChatColor.RED + "[Pago] " + reject);
            return true;
        }

        // Cooldown anti-spam.
        long now = System.currentTimeMillis();
        long cdMs = cooldownSeconds() * 1000L;
        Long last = lastPay.get(p.getUniqueId());
        if (last != null && now - last < cdMs) {
            long left = (cdMs - (now - last) + 999) / 1000;
            p.sendMessage(ChatColor.GRAY + "[Pago] Espera " + left + "s antes de volver a pagar.");
            return true;
        }

        // Pagos grandes: pedir confirmacion para que un dedazo no vacie la cartera.
        BigDecimal threshold = confirmThreshold();
        if (threshold != null && amount.compareTo(threshold) >= 0) {
            pending.put(p.getUniqueId(),
                    new Pending(target.getUniqueId(), target.getName(), amount, now + 30_000L));
            p.sendMessage(ChatColor.YELLOW + "[Pago] Vas a entregar " + ChatColor.WHITE + "$" + plain(amount)
                    + ChatColor.YELLOW + " a " + ChatColor.WHITE + target.getName() + ChatColor.YELLOW + ".");
            p.sendMessage(ChatColor.GRAY + "Confirma con " + ChatColor.WHITE + "/pagar confirmar"
                    + ChatColor.GRAY + " (30s) o " + ChatColor.WHITE + "/pagar cancelar" + ChatColor.GRAY + ".");
            return true;
        }

        doPay(p, target.getUniqueId(), target.getName(), amount);
        return true;
    }

    private void confirm(Player p) {
        Pending pen = pending.remove(p.getUniqueId());
        if (pen == null) {
            p.sendMessage(ChatColor.GRAY + "[Pago] No tienes ningun pago pendiente.");
            return;
        }
        if (System.currentTimeMillis() > pen.expiresAt()) {
            p.sendMessage(ChatColor.RED + "[Pago] La confirmacion caduco. Repite el pago.");
            return;
        }
        Player target = Bukkit.getPlayer(pen.target());
        String reject = validateTarget(p, target);
        if (reject != null) {
            p.sendMessage(ChatColor.RED + "[Pago] " + reject);
            return;
        }
        doPay(p, pen.target(), target.getName(), pen.amount());
    }

    /** Devuelve un motivo de rechazo (texto) o null si el receptor es valido (online, cerca, no tu mismo). */
    private String validateTarget(Player payer, Player target) {
        if (target == null || !target.isOnline()) {
            return "Ese jugador no esta conectado.";
        }
        if (target.getUniqueId().equals(payer.getUniqueId())) {
            return "No puedes pagarte a ti mismo.";
        }
        double radius = radius();
        if (radius > 0) {
            if (!target.getWorld().equals(payer.getWorld())
                    || target.getLocation().distanceSquared(payer.getLocation()) > radius * radius) {
                return "Tienes que estar cerca de " + target.getName() + " para darle el dinero en mano.";
            }
        }
        return null;
    }

    private void doPay(Player payer, UUID targetId, String targetName, BigDecimal amount) {
        final UUID payerId = payer.getUniqueId();
        lastPay.put(payerId, System.currentTimeMillis());
        economy.transfer(payerId, MoneyType.EFECTIVO, targetId, MoneyType.EFECTIVO, amount, "pay-hand")
                .thenAccept(res -> run(() -> {
                    if (res != null && res.success()) {
                        payer.sendMessage(ChatColor.GREEN + "[Pago] Le diste " + ChatColor.WHITE + "$" + plain(amount)
                                + ChatColor.GREEN + " en efectivo a " + ChatColor.WHITE + targetName + ChatColor.GREEN + ".");
                        Player target = Bukkit.getPlayer(targetId);
                        if (target != null && target.isOnline()) {
                            target.sendMessage(ChatColor.GREEN + "[Pago] " + ChatColor.WHITE + payer.getName()
                                    + ChatColor.GREEN + " te dio " + ChatColor.WHITE + "$" + plain(amount)
                                    + ChatColor.GREEN + " en efectivo.");
                        }
                    } else {
                        // El fallo mas comun es saldo insuficiente: el transfer es atomico y no descuenta nada.
                        lastPay.remove(payerId); // no penalizar el cooldown si no se pago
                        String code = (res == null) ? "error" : res.errorCode();
                        if (code != null && code.toUpperCase(java.util.Locale.ROOT).contains("FONDOS")) {
                            payer.sendMessage(ChatColor.RED + "[Pago] No te alcanza el efectivo.");
                        } else {
                            payer.sendMessage(ChatColor.RED + "[Pago] No se pudo completar el pago (" + code + ").");
                        }
                    }
                }))
                .exceptionally(ex -> { run(() -> {
                    lastPay.remove(payerId);
                    payer.sendMessage(ChatColor.RED + "[Pago] Error al procesar: " + ex.getMessage());
                }); return null; });
    }

    // ─── config ──────────────────────────────────────────────

    private double radius() {
        return plugin.getConfig().getDouble("economy.pay.radius", 8.0);
    }

    private long cooldownSeconds() {
        return plugin.getConfig().getLong("economy.pay.cooldown-seconds", 3L);
    }

    /** Tope por pago, o null si esta a 0/negativo (sin tope). */
    private BigDecimal maxPerPay() {
        double v = plugin.getConfig().getDouble("economy.pay.max-per-pay", 0.0);
        return v > 0 ? BigDecimal.valueOf(v) : null;
    }

    /** A partir de este monto se pide confirmacion, o null si esta a 0 (sin confirmacion). */
    private BigDecimal confirmThreshold() {
        double v = plugin.getConfig().getDouble("economy.pay.confirm-threshold", 50000.0);
        return v > 0 ? BigDecimal.valueOf(v) : null;
    }

    // ─── helpers ──────────────────────────────────────────────

    private static BigDecimal parse(String s) {
        try { return new BigDecimal(s.replace(",", "")); } catch (NumberFormatException e) { return null; }
    }

    private static String plain(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private void run(Runnable r) { Bukkit.getScheduler().runTask(plugin, r); }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(java.util.Locale.ROOT);
            List<String> out = new ArrayList<>();
            if (sender instanceof Player self) {
                for (Player pl : Bukkit.getOnlinePlayers()) {
                    if (pl.getUniqueId().equals(self.getUniqueId())) continue;
                    if (pl.getName().toLowerCase(java.util.Locale.ROOT).startsWith(prefix)) out.add(pl.getName());
                }
            }
            return out;
        }
        return List.of();
    }
}
