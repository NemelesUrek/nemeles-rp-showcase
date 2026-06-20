package com.nemeles.npcai;

import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.ChatColor;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.TextDisplay;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Holograma (TextDisplay) sobre el NPC mostrando lo que dice. Crossplay: en Java siempre;
 *  en Bedrock según la versión de Geyser (el chat sigue siendo el canal fiable). */
public final class HologramManager {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();

    private final Plugin plugin;
    private final NamespacedKey tagKey;
    private final boolean enabled;
    private final double heightOffset;
    private final Map<UUID, UUID> holos = new HashMap<>();        // npcEntity -> textDisplay
    private final Map<UUID, BukkitTask> hideTasks = new HashMap<>();
    private final Map<UUID, BukkitTask> thinkTasks = new HashMap<>();  // animacion "..." mientras la IA piensa

    public HologramManager(Plugin plugin, boolean enabled, double heightOffset) {
        this.plugin = plugin;
        this.enabled = enabled;
        this.heightOffset = heightOffset;
        this.tagKey = new NamespacedKey(plugin, "holo");
    }

    /** Animación "escribiendo..." con puntos que crecen, hasta que llega la respuesta. */
    public void showThinking(Entity npc, String headerLegacy) {
        if (!enabled || npc == null) return;
        TextDisplay td = getOrSpawn(npc);
        if (td == null) return;
        UUID npcId = npc.getUniqueId();
        td.teleport(npc.getLocation().add(0, npc.getHeight() + heightOffset, 0));
        cancelThink(npcId);
        BukkitTask prevHide = hideTasks.remove(npcId);
        if (prevHide != null) prevHide.cancel();
        final int[] frame = {0};
        BukkitTask task = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            UUID tdId = holos.get(npcId);
            if (tdId == null) return;
            Entity e = plugin.getServer().getEntity(tdId);
            if (!(e instanceof TextDisplay disp) || !disp.isValid()) return;
            String dots = ".".repeat((frame[0] % 3) + 1);
            disp.text(LEGACY.deserialize(ChatColor.translateAlternateColorCodes('&', headerLegacy + "\n&7" + dots)));
            frame[0]++;
        }, 0L, 6L);
        thinkTasks.put(npcId, task);
    }

    public void show(Entity npc, String legacyAmp, int seconds) {
        if (!enabled || npc == null) return;
        TextDisplay td = getOrSpawn(npc);
        if (td == null) return;
        cancelThink(npc.getUniqueId());
        td.text(LEGACY.deserialize(ChatColor.translateAlternateColorCodes('&', legacyAmp)));
        td.teleport(npc.getLocation().add(0, npc.getHeight() + heightOffset, 0));
        UUID npcId = npc.getUniqueId();
        BukkitTask prev = hideTasks.remove(npcId);
        if (prev != null) prev.cancel();
        if (seconds > 0) {
            hideTasks.put(npcId, plugin.getServer().getScheduler().runTaskLater(plugin, () -> hide(npcId), seconds * 20L));
        }
    }

    private TextDisplay getOrSpawn(Entity npc) {
        UUID npcId = npc.getUniqueId();
        UUID tdId = holos.get(npcId);
        if (tdId != null) {
            Entity e = plugin.getServer().getEntity(tdId);
            if (e instanceof TextDisplay td && td.isValid()) return td;
            holos.remove(npcId);
        }
        Location loc = npc.getLocation().add(0, npc.getHeight() + heightOffset, 0);
        if (loc.getWorld() == null) return null;
        TextDisplay td = loc.getWorld().spawn(loc, TextDisplay.class, t -> {
            t.setBillboard(Display.Billboard.CENTER);
            t.setSeeThrough(false);
            t.setShadowed(true);
            try { t.setBackgroundColor(Color.fromARGB(170, 14, 14, 20)); }
            catch (Throwable ignored) { t.setDefaultBackground(true); }
            t.setLineWidth(180);
            t.setPersistent(false);
            t.getPersistentDataContainer().set(tagKey, PersistentDataType.INTEGER, 1);
        });
        holos.put(npcId, td.getUniqueId());
        return td;
    }

    private void cancelThink(UUID npcId) {
        BukkitTask t = thinkTasks.remove(npcId);
        if (t != null) t.cancel();
    }

    public void hide(UUID npcId) {
        cancelThink(npcId);
        BukkitTask t = hideTasks.remove(npcId);
        if (t != null) t.cancel();
        UUID tdId = holos.remove(npcId);
        if (tdId != null) {
            Entity e = plugin.getServer().getEntity(tdId);
            if (e != null) e.remove();
        }
    }

    public void removeAll() {
        for (UUID tdId : holos.values()) {
            Entity e = plugin.getServer().getEntity(tdId);
            if (e != null) e.remove();
        }
        holos.clear();
        for (BukkitTask t : hideTasks.values()) t.cancel();
        hideTasks.clear();
        for (BukkitTask t : thinkTasks.values()) t.cancel();
        thinkTasks.clear();
    }

    /** Elimina hologramas huérfanos de un arranque anterior. */
    public void sweepOrphans() {
        for (World w : plugin.getServer().getWorlds()) {
            for (TextDisplay td : w.getEntitiesByClass(TextDisplay.class)) {
                Integer v = td.getPersistentDataContainer().get(tagKey, PersistentDataType.INTEGER);
                if (v != null && v == 1) td.remove();
            }
        }
    }
}
