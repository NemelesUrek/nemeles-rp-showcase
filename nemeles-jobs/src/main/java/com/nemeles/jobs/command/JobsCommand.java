package com.nemeles.jobs.command;

import com.nemeles.jobs.JobManager;
import com.nemeles.jobs.JobProgress;
import com.nemeles.jobs.PlayerJobs;
import com.nemeles.jobs.model.JobAction;
import com.nemeles.jobs.model.JobDefinition;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Locale;

/** /skills (alias /jobs) &lt;list|stats|top&gt; — habilidades abiertas: todos suben todo practicando. */
public final class JobsCommand implements CommandExecutor {

    private final JobManager jobs;

    public JobsCommand(JobManager jobs) {
        this.jobs = jobs;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("[Habilidades] Solo desde el juego.");
            return true;
        }
        if (args.length == 0) { help(player); return true; }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "list" -> list(player);
            case "stats" -> stats(player);
            case "top" -> {
                if (args.length < 2) { player.sendMessage(ChatColor.RED + "Uso: /skills top <habilidad>"); return true; }
                jobs.showTop(player, args[1].toLowerCase(Locale.ROOT), 10);
            }
            case "set" -> {
                if (!player.hasPermission("nemeles.jobs.admin")) { player.sendMessage(ChatColor.RED + "Sin permiso."); return true; }
                if (args.length < 4) { player.sendMessage(ChatColor.RED + "Uso: /skills set <jugador> <habilidad> <nivel>"); return true; }
                Player t = Bukkit.getPlayerExact(args[1]);
                if (t == null) { player.sendMessage(ChatColor.RED + "Jugador no conectado."); return true; }
                int lvl;
                try { lvl = Integer.parseInt(args[3]); } catch (NumberFormatException e) { player.sendMessage(ChatColor.RED + "Nivel invalido."); return true; }
                if (jobs.adminSetLevel(t, args[2].toLowerCase(Locale.ROOT), lvl)) {
                    player.sendMessage(ChatColor.GREEN + "[Habilidades] " + t.getName() + " -> " + args[2].toLowerCase(Locale.ROOT) + " Lv" + lvl);
                } else {
                    player.sendMessage(ChatColor.RED + "Habilidad desconocida o jugador sin datos cargados.");
                }
            }
            default -> help(player);
        }
        return true;
    }

    private void help(Player p) {
        p.sendMessage(ChatColor.AQUA + "== Habilidades ==");
        p.sendMessage(ChatColor.GRAY + "Sube tus habilidades simplemente practicandolas (minar, cosechar...).");
        p.sendMessage(ChatColor.GRAY + "/skills list " + ChatColor.DARK_GRAY + "- todas las habilidades y tu nivel");
        p.sendMessage(ChatColor.GRAY + "/skills stats " + ChatColor.DARK_GRAY + "- tu progreso detallado");
        p.sendMessage(ChatColor.GRAY + "/skills top <habilidad> " + ChatColor.DARK_GRAY + "- ranking");
        if (p.hasPermission("nemeles.jobs.admin")) {
            p.sendMessage(ChatColor.GRAY + "/skills set <jugador> <habilidad> <nivel> " + ChatColor.DARK_GRAY + "- (admin) fijar nivel");
        }
    }

    private void list(Player p) {
        PlayerJobs pj = jobs.get(p.getUniqueId());
        p.sendMessage(ChatColor.AQUA + "== Tus habilidades ==");
        for (JobDefinition def : jobs.registry().all()) {
            int level = (pj != null && pj.has(def.id())) ? pj.get(def.id()).level() : 1;
            p.sendMessage(ChatColor.GRAY + "- " + ChatColor.WHITE + def.displayName()
                    + ChatColor.DARK_GRAY + " (" + def.id() + ") " + ChatColor.GREEN + "Lv" + level);
        }
        p.sendMessage(ChatColor.DARK_GRAY + "Todas se suben practicando; no hay que unirse.");
    }

    private void stats(Player p) {
        PlayerJobs pj = jobs.get(p.getUniqueId());
        if (pj == null || pj.all().isEmpty()) {
            p.sendMessage(ChatColor.YELLOW + "[Habilidades] Aun no has practicado ninguna. ¡Empieza a minar o cosechar!");
            return;
        }
        p.sendMessage(ChatColor.AQUA + "== Tu progreso ==");
        for (var e : pj.all().entrySet()) {
            JobDefinition def = jobs.registry().get(e.getKey());
            JobProgress prog = e.getValue();
            String name = def != null ? def.displayName() : e.getKey();
            long next = jobs.curve().xpToNext(prog.level());
            p.sendMessage(ChatColor.WHITE + name + ChatColor.GRAY + " - Nivel " + ChatColor.GREEN + prog.level()
                    + ChatColor.GRAY + " (" + prog.xp() + "/" + next + " XP, total " + prog.totalXp() + ")");
            if (def != null) {
                String unlock = nextUnlock(def, prog.level());
                if (unlock != null) p.sendMessage(ChatColor.DARK_GRAY + "   Proximo desbloqueo: " + unlock);
            }
        }
    }

    private String nextUnlock(JobDefinition def, int level) {
        int bestLevel = Integer.MAX_VALUE;
        String bestMat = null;
        for (JobAction a : def.actions().values()) {
            if (a.unlockLevel() > level && a.unlockLevel() < bestLevel) {
                bestLevel = a.unlockLevel();
                bestMat = a.material().name().toLowerCase(Locale.ROOT);
            }
        }
        return bestMat == null ? null : (bestMat + " (Lv" + bestLevel + ")");
    }
}
