package com.nemeles.jobs.employment;

import com.nemeles.jobs.zone.WorkZone;
import com.nemeles.jobs.zone.WorkZoneManager;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Locale;

/** /empleo (alias /job, /trabajo) &lt;list|join|leave|info|zonas&gt; */
public final class EmploymentCommand implements CommandExecutor {

    private final EmploymentManager employment;
    private final WorkZoneManager workZones;

    public EmploymentCommand(EmploymentManager employment, WorkZoneManager workZones) {
        this.employment = employment;
        this.workZones = workZones;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("[Empleo] Solo desde el juego.");
            return true;
        }
        String sub = args.length == 0 ? "info" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "list" -> employment.list(player);
            case "join", "unirse" -> {
                if (args.length < 2) { player.sendMessage(ChatColor.RED + "Uso: /empleo join <trabajo>"); return true; }
                employment.join(player, args[1].toLowerCase(Locale.ROOT));
            }
            case "leave", "renunciar" -> employment.leave(player);
            case "zonas", "zona", "zones" -> showZones(player);
            default -> employment.info(player);
        }
        return true;
    }

    private void showZones(Player p) {
        if (workZones == null || !workZones.enabled() || workZones.zones().isEmpty()) {
            p.sendMessage(ChatColor.GRAY + "[Empleo] No hay zonas de trabajo definidas en este servidor.");
            return;
        }
        String job = employment.getJob(p.getUniqueId());
        WorkZone here = workZones.zoneAt(p.getLocation());
        p.sendMessage(ChatColor.AQUA + "== Zonas de trabajo ==");
        for (WorkZone z : workZones.zones()) {
            boolean mine = z.jobId.equals(job);
            String tag = (here != null && here.id.equals(z.id)) ? ChatColor.GREEN + " «estás aquí»" : "";
            p.sendMessage((mine ? ChatColor.GREEN : ChatColor.GRAY) + "- " + ChatColor.WHITE + z.display
                    + ChatColor.DARK_GRAY + " [" + z.jobId + "]" + ChatColor.GREEN + " +" + z.bonusPercent() + "% XP" + tag);
        }
        p.sendMessage(ChatColor.DARK_GRAY + "Trabaja tu oficio DENTRO de su zona para el bonus; fuera ganas menos.");
    }
}
