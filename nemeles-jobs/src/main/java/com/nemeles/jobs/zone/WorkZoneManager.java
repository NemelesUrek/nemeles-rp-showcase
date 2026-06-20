package com.nemeles.jobs.zone;

import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.title.Title;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Zonas de trabajo: dentro de la zona de TU oficio ganas más XP; si practicas un oficio que TIENE zonas
 * fuera de ellas, ganas menos (penalización por no trabajar donde toca). Se combina con la capa de empleo
 * (un oficio por jugador) — así puedes cambiar de empleo cuando quieras, pero te conviene especializarte y
 * trabajar en tu zona. Avisos de entrada/salida vía título/actionbar. Cuboides definidos en config.
 */
public final class WorkZoneManager {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();

    private final boolean enabled;
    private final double outPenalty;
    private final List<WorkZone> zones = new ArrayList<>();
    private final Set<String> zonedJobs = new HashSet<>();
    private final Map<UUID, String> current = new ConcurrentHashMap<>();

    public WorkZoneManager(FileConfiguration cfg) {
        this.enabled = cfg.getBoolean("work-zones.enabled", true);
        this.outPenalty = cfg.getDouble("work-zones.out-of-zone-penalty", 0.7);
        ConfigurationSection root = cfg.getConfigurationSection("work-zones.zones");
        if (root != null) {
            for (String id : root.getKeys(false)) {
                ConfigurationSection s = root.getConfigurationSection(id);
                if (s == null) continue;
                List<Integer> mn = s.getIntegerList("min");
                List<Integer> mx = s.getIntegerList("max");
                if (mn.size() < 3 || mx.size() < 3) continue;
                String job = s.getString("job", "");
                WorkZone z = new WorkZone(id, job, s.getString("world", "world"),
                        s.getString("display", id), s.getDouble("bonus", 1.5),
                        mn.get(0), mn.get(1), mn.get(2), mx.get(0), mx.get(1), mx.get(2));
                zones.add(z);
                if (!job.isBlank()) zonedJobs.add(job);
            }
        }
    }

    public boolean enabled() { return enabled; }
    public List<WorkZone> zones() { return zones; }

    public WorkZone zoneAt(Location l) {
        for (WorkZone z : zones) if (z.contains(l)) return z;
        return null;
    }

    /**
     * Multiplicador de XP para practicar `skillId` aquí:
     *  - dentro de una zona de ese oficio: bonus de la zona.
     *  - fuera, pero el oficio TIENE zonas: penalización.
     *  - el oficio no tiene zonas: 1.0 (neutro).
     */
    public double xpMultiplier(Player p, String skillId) {
        if (!enabled || zones.isEmpty()) return 1.0;
        Location loc = p.getLocation();
        for (WorkZone z : zones) {
            if (z.jobId.equals(skillId) && z.contains(loc)) return z.bonus;
        }
        return zonedJobs.contains(skillId) ? outPenalty : 1.0;
    }

    /** Detecta entrada/salida de zona y avisa al jugador (lo llama un tick del plugin). */
    public void tickPlayer(Player p, String employmentJob) {
        if (!enabled || zones.isEmpty()) return;
        WorkZone z = zoneAt(p.getLocation());
        String now = z == null ? null : z.id;
        String prev = current.get(p.getUniqueId());
        if (Objects.equals(now, prev)) return;
        if (now == null) current.remove(p.getUniqueId()); else current.put(p.getUniqueId(), now);

        if (z != null) {
            if (z.jobId.equals(employmentJob)) {
                p.showTitle(Title.title(
                        LEGACY.deserialize(ChatColor.GREEN + "⛏ Zona de trabajo"),
                        LEGACY.deserialize(ChatColor.GRAY + z.display + ChatColor.GREEN + "  +" + z.bonusPercent() + "% XP"),
                        Title.Times.times(Duration.ofMillis(300), Duration.ofMillis(1800), Duration.ofMillis(500))));
                try { p.playSound(p.getLocation(), "block.note_block.pling", 0.7f, 1.4f); } catch (Throwable ignored) { }
            } else {
                p.sendActionBar(LEGACY.deserialize(ChatColor.GRAY + "Zona de trabajo: " + ChatColor.WHITE + z.display
                        + ChatColor.GRAY + " (oficio '" + z.jobId + "'). Trabaja aquí ese oficio para el bonus."));
            }
        } else if (prev != null) {
            p.sendActionBar(LEGACY.deserialize(ChatColor.GRAY + "Saliste de la zona de trabajo."));
        }
    }

    public void evict(UUID u) { current.remove(u); }
}
