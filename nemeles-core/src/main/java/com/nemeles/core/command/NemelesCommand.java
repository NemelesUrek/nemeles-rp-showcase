package com.nemeles.core.command;

import com.nemeles.core.api.economy.MoneyType;
import com.nemeles.core.api.economy.TransactionResult;
import com.nemeles.core.db.HikariDatabaseProvider;
import com.nemeles.core.economy.DualEconomyService;
import com.nemeles.core.profile.ProfileServiceImpl;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.Plugin;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Statement;
import java.util.Locale;
import java.util.UUID;

/**
 * /nemeles &lt;db|info|bal|give|take|pay|launder&gt; – diagnostico del nucleo y utilidades de prueba de la
 * economia (mientras llega el puente Vault en el siguiente milestone).
 */
public final class NemelesCommand implements CommandExecutor {

    private final Plugin plugin;
    private final HikariDatabaseProvider db;
    private final DualEconomyService economy;
    private final ProfileServiceImpl profiles;

    public NemelesCommand(Plugin plugin, HikariDatabaseProvider db,
                          DualEconomyService economy, ProfileServiceImpl profiles) {
        this.plugin = plugin;
        this.db = db;
        this.economy = economy;
        this.profiles = profiles;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            help(sender);
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "info" -> {
                sender.sendMessage("§b[NemelesCore] §fv" + plugin.getDescription().getVersion());
                sender.sendMessage("§7Motor de BD: §f" + (db.isSqlite() ? "SQLite" : "MySQL"));
            }
            case "db" -> testDb(sender);
            case "bal", "balance" -> balance(sender, args);
            case "give" -> giveTake(sender, args, true);
            case "take" -> giveTake(sender, args, false);
            case "pay" -> pay(sender, args);
            case "launder", "lavar" -> launder(sender, args);
            default -> help(sender);
        }
        return true;
    }

    private void help(CommandSender s) {
        s.sendMessage("§b[NemelesCore] §7Comandos:");
        s.sendMessage("§f/nemeles info §7- version y motor de BD");
        s.sendMessage("§f/nemeles db §7- prueba la conexion");
        s.sendMessage("§f/nemeles bal [jugador] §7- ver los 4 saldos");
        s.sendMessage("§f/nemeles give <jug> <efectivo|banco|sucio|limpio> <monto> §7- dar dinero");
        s.sendMessage("§f/nemeles take <jug> <tipo> <monto> §7- quitar dinero");
        s.sendMessage("§f/nemeles pay <de> <a> <monto> §7- transferir efectivo");
        s.sendMessage("§f/nemeles launder <jug> <monto> [comision] §7- lavar (sucio->limpio)");
    }

    private void testDb(CommandSender sender) {
        sender.sendMessage("§7Probando conexion a la base de datos...");
        db.dbExecutor().execute(() -> {
            boolean ok;
            String message;
            try (Connection con = db.dataSource().getConnection();
                 Statement st = con.createStatement()) {
                st.execute("SELECT 1");
                ok = true;
                message = "OK";
            } catch (Exception e) {
                ok = false;
                message = e.getMessage();
            }
            final boolean fok = ok;
            final String fmsg = message;
            run(() -> sender.sendMessage(fok
                    ? "§a[NemelesCore] Conexion OK (" + (db.isSqlite() ? "SQLite" : "MySQL") + ")"
                    : "§c[NemelesCore] Fallo: " + fmsg));
        });
    }

    private void balance(CommandSender sender, String[] args) {
        String name = (args.length >= 2) ? args[1] : sender.getName();
        UUID target = uuidOf(name);
        economy.balances(target).thenAccept(map -> run(() -> {
            sender.sendMessage("§b[NemelesCore] §7Saldos de §f" + name + "§7:");
            sender.sendMessage("  §aEfectivo: §f" + fmt(map.get(MoneyType.EFECTIVO)));
            sender.sendMessage("  §aBanco:    §f" + fmt(map.get(MoneyType.BANCO)));
            sender.sendMessage("  §cSucio:    §f" + fmt(map.get(MoneyType.SUCIO)));
            sender.sendMessage("  §2Limpio:   §f" + fmt(map.get(MoneyType.LIMPIO)));
        })).exceptionally(ex -> { run(() -> sender.sendMessage("§cError: " + ex.getMessage())); return null; });
    }

    private void giveTake(CommandSender sender, String[] args, boolean give) {
        if (args.length < 4) {
            sender.sendMessage("§cUso: /nemeles " + (give ? "give" : "take") + " <jugador> <tipo> <monto>");
            return;
        }
        UUID target = uuidOf(args[1]);
        MoneyType type = parseType(args[2]);
        if (type == null) { sender.sendMessage("§cTipo invalido. Usa: efectivo, banco, sucio o limpio."); return; }
        BigDecimal amount = parseAmount(args[3]);
        if (amount == null) { sender.sendMessage("§cMonto invalido."); return; }
        String reason = "admin-" + (give ? "give" : "take");
        var future = give ? economy.deposit(target, type, amount, reason)
                          : economy.withdraw(target, type, amount, reason);
        future.thenAccept(res -> run(() -> sendResult(sender, res,
                (give ? "Dado " : "Quitado ") + fmt(amount) + " (" + type + ") a " + args[1])))
              .exceptionally(ex -> { run(() -> sender.sendMessage("§cError: " + ex.getMessage())); return null; });
    }

    private void pay(CommandSender sender, String[] args) {
        if (args.length < 4) { sender.sendMessage("§cUso: /nemeles pay <de> <a> <monto>"); return; }
        UUID from = uuidOf(args[1]);
        UUID to = uuidOf(args[2]);
        BigDecimal amount = parseAmount(args[3]);
        if (amount == null) { sender.sendMessage("§cMonto invalido."); return; }
        economy.transfer(from, MoneyType.EFECTIVO, to, MoneyType.EFECTIVO, amount, "admin-pay")
                .thenAccept(res -> run(() -> sendResult(sender, res,
                        "Transferido " + fmt(amount) + " de " + args[1] + " a " + args[2])))
                .exceptionally(ex -> { run(() -> sender.sendMessage("§cError: " + ex.getMessage())); return null; });
    }

    private void launder(CommandSender sender, String[] args) {
        if (args.length < 3) { sender.sendMessage("§cUso: /nemeles launder <jugador> <monto> [comision 0-0.99]"); return; }
        UUID target = uuidOf(args[1]);
        BigDecimal amount = parseAmount(args[2]);
        if (amount == null) { sender.sendMessage("§cMonto invalido."); return; }
        BigDecimal fee = (args.length >= 4) ? parseAmount(args[3]) : defaultFee();
        if (fee == null) { sender.sendMessage("§cComision invalida."); return; }
        economy.launder(target, amount, fee, "admin-test")
                .thenAccept(res -> run(() -> sendResult(sender, res,
                        "Lavado " + fmt(amount) + " (comision " + fee + ") de " + args[1])))
                .exceptionally(ex -> { run(() -> sender.sendMessage("§cError: " + ex.getMessage())); return null; });
    }

    // ─── helpers ──────────────────────────────────────────────

    private void sendResult(CommandSender sender, TransactionResult res, String okMsg) {
        if (res.success()) {
            sender.sendMessage("§a[NemelesCore] " + okMsg + " §7(nuevo saldo: §f" + fmt(res.newBalance()) + "§7, tx#" + res.txId() + ")");
        } else {
            sender.sendMessage("§c[NemelesCore] Operacion fallida: " + res.errorCode());
        }
    }

    @SuppressWarnings("deprecation")
    private UUID uuidOf(String name) {
        var online = Bukkit.getPlayerExact(name);
        if (online != null) {
            return online.getUniqueId();
        }
        return Bukkit.getOfflinePlayer(name).getUniqueId();
    }

    private static MoneyType parseType(String s) {
        try {
            return MoneyType.valueOf(s.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private BigDecimal defaultFee() {
        return BigDecimal.valueOf(plugin.getConfig().getDouble("economy.launder.default-fee", 0.30));
    }

    private static BigDecimal parseAmount(String s) {
        try {
            BigDecimal v = new BigDecimal(s);
            return v.signum() < 0 ? null : v;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String fmt(BigDecimal v) {
        return v == null ? "?" : "$" + v.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
    }

    private void run(Runnable r) {
        Bukkit.getScheduler().runTask(plugin, r);
    }
}
