package com.nemeles.jobs.command;

import com.nemeles.jobs.JobManager;
import com.nemeles.jobs.perk.PerkDef;
import com.nemeles.jobs.perk.PerkRegistry;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Locale;

/** /perk — elegir y ver perks de habilidades (1 de 2 cada 10 niveles). */
public final class PerkCommand implements CommandExecutor {

    private final JobManager jobs;
    private final PerkRegistry perks;

    public PerkCommand(JobManager jobs, PerkRegistry perks) {
        this.jobs = jobs;
        this.perks = perks;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player p)) { sender.sendMessage("Solo jugadores."); return true; }
        if (args.length == 0) { pending(p); return true; }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "elegir", "choose" -> {
                if (args.length < 4) { p.sendMessage(ChatColor.RED + "Uso: /perk elegir <habilidad> <nivel> <A|B>"); return true; }
                int tier;
                try { tier = Integer.parseInt(args[2]); } catch (NumberFormatException e) { p.sendMessage(ChatColor.RED + "Nivel inválido."); return true; }
                char opt = Character.toUpperCase(args[3].charAt(0));
                jobs.choosePerk(p, args[1].toLowerCase(Locale.ROOT), tier, opt);
            }
            case "info" -> {
                if (args.length < 2) { p.sendMessage(ChatColor.RED + "Uso: /perk info <habilidad>"); return true; }
                info(p, args[1].toLowerCase(Locale.ROOT));
            }
            default -> pending(p);
        }
        return true;
    }

    private void pending(Player p) {
        p.sendMessage(ChatColor.AQUA + "== Perks pendientes ==");
        boolean any = false;
        for (String skill : new String[]{"miner", "farmer", "medic"}) {
            List<Integer> tiers = jobs.pendingPerkTiers(p.getUniqueId(), skill);
            if (tiers.isEmpty()) continue;
            any = true;
            for (int t : tiers) {
                PerkDef d = perks.get(skill, t);
                if (d == null) continue;
                p.sendMessage(ChatColor.YELLOW + skill + " L" + t + ChatColor.GRAY + ": "
                        + ChatColor.WHITE + "A) " + d.a.displayName + ChatColor.GRAY + " · "
                        + ChatColor.WHITE + "B) " + d.b.displayName);
                p.sendMessage(ChatColor.DARK_GRAY + "   /perk elegir " + skill + " " + t + " A|B");
            }
        }
        if (!any) p.sendMessage(ChatColor.GRAY + "No tienes perks pendientes. Sube habilidades a múltiplos de 10.");
    }

    private void info(Player p, String skill) {
        p.sendMessage(ChatColor.AQUA + "== Perks de " + skill + " ==");
        boolean any = false;
        for (var e : perks.tiers(skill).entrySet()) {
            char c = jobs.perkChoice(p.getUniqueId(), skill, e.getKey());
            if (c == '\0') continue;
            any = true;
            var choice = e.getValue().byOption(c);
            p.sendMessage(ChatColor.GRAY + "L" + e.getKey() + ": " + ChatColor.WHITE + (choice != null ? choice.displayName : "?"));
        }
        if (!any) p.sendMessage(ChatColor.GRAY + "Sin perks elegidos en esa habilidad todavía.");
    }
}
