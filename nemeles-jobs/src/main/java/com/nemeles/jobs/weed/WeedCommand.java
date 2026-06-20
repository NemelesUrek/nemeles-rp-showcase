package com.nemeles.jobs.weed;

import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Locale;

/** /weed &lt;semilla|procesar|vender|rastrear|info&gt; */
public final class WeedCommand implements CommandExecutor {

    private final WeedManager weed;

    public WeedCommand(WeedManager weed) {
        this.weed = weed;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("[Marihuana] Solo desde el juego.");
            return true;
        }
        String sub = args.length == 0 ? "info" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "semilla", "comprar" -> {
                int n = 1;
                if (args.length >= 2) {
                    try { n = Math.max(1, Integer.parseInt(args[1])); } catch (NumberFormatException ignored) {}
                }
                weed.buySeed(player, n);
            }
            case "procesar" -> weed.process(player);
            case "vender" -> weed.sell(player);
            case "rastrear" -> {
                if (!player.hasPermission("nemeles.jobs.police")) {
                    player.sendMessage(ChatColor.RED + "No tienes permiso para rastrear.");
                    return true;
                }
                weed.detect(player, player.getLocation());
            }
            default -> help(player);
        }
        return true;
    }

    private void help(Player p) {
        p.sendMessage(ChatColor.AQUA + "== Marihuana ==");
        p.sendMessage(ChatColor.GRAY + "Requiere Agricultura nivel " + ChatColor.WHITE + weed.unlockLevel()
                + ChatColor.GRAY + " para cultivar.");
        p.sendMessage(ChatColor.GRAY + "/weed semilla [n] " + ChatColor.DARK_GRAY + "- comprar semillas (efectivo)");
        p.sendMessage(ChatColor.GRAY + "Clic derecho con la semilla en una ZONA de cultivo para plantar.");
        p.sendMessage(ChatColor.GRAY + "/weed procesar " + ChatColor.DARK_GRAY + "- 3 cogollos -> 1 bolsa");
        p.sendMessage(ChatColor.GRAY + "/weed vender " + ChatColor.DARK_GRAY + "- vender bolsas (dinero SUCIO)");
        p.sendMessage(ChatColor.DARK_GRAY + "Luego lava el dinero: /nemeles launder o la app del telefono.");
    }
}
