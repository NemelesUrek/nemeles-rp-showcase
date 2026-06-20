package com.nemeles.npcai;

import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.npc.NPCRegistry;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

/**
 * BANTER entre NPCs: cuando dos (o más) NPCs configurados (p.ej. Simón y Maid) están CERCA, no están
 * hablando con ningún jugador, y hay alguien cerca que lo disfrute, sueltan SOLOS un intercambio de
 * bromas: lo ve el chat de los jugadores cercanos + holograma sobre quien habla + blips de voz.
 * Crossplay (chat + holo + sonido vanilla). Requiere Citizens. A prueba de fallos: si algo peta, calla.
 *
 * Config en banter.* (pairs con members + exchanges). Cada exchange es una cadena con réplicas
 * separadas por "||", y cada réplica es "claveNPC: texto" (la clave dice QUIÉN la dice).
 */
public final class BanterManager {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();

    /** members = claves de los NPCs que deben estar juntos; exchanges = lista de guiones (cada uno: líneas [clave,texto]). */
    private record Pair(List<String> members, List<List<String[]>> exchanges) { }

    private final Plugin plugin;
    private final ConversationManager mgr;
    private final HologramManager holo;
    private final Supplier<AiConfig> cfg;

    private final boolean enabled;
    private final double range, hearRadius;
    private final long cooldownMs, lineDelayTicks;
    private final double chance;
    private final int holoSeconds;
    private final List<Pair> pairs = new ArrayList<>();
    private final long[] lastByPair;

    public BanterManager(Plugin plugin, ConversationManager mgr, HologramManager holo, Supplier<AiConfig> cfg) {
        this.plugin = plugin;
        this.mgr = mgr;
        this.holo = holo;
        this.cfg = cfg;
        org.bukkit.configuration.file.FileConfiguration c = plugin.getConfig();
        enabled        = c.getBoolean("banter.enabled", true);
        range          = c.getDouble("banter.range", 7);
        hearRadius     = c.getDouble("banter.hear-radius", 16);
        cooldownMs     = Math.max(10, c.getInt("banter.cooldown-seconds", 90)) * 1000L;
        chance         = Math.max(0.0, Math.min(1.0, c.getDouble("banter.chance", 0.55)));
        lineDelayTicks = Math.max(20, c.getInt("banter.line-delay-ticks", 50));
        holoSeconds    = Math.max(3, c.getInt("banter.holo-seconds", 6));

        for (Map<?, ?> m : c.getMapList("banter.pairs")) {
            try {
                Object mem = m.get("members");
                Object exs = m.get("exchanges");
                if (!(mem instanceof List) || !(exs instanceof List)) continue;
                List<String> members = new ArrayList<>();
                for (Object o : (List<?>) mem) members.add(String.valueOf(o).toLowerCase().trim());
                List<List<String[]>> exchanges = new ArrayList<>();
                for (Object exObj : (List<?>) exs) {
                    List<String[]> lines = new ArrayList<>();
                    for (String part : String.valueOf(exObj).split("\\|\\|")) {
                        int idx = part.indexOf(':');
                        if (idx <= 0) continue;
                        String key = part.substring(0, idx).trim().toLowerCase();
                        String text = part.substring(idx + 1).trim();
                        if (!key.isEmpty() && !text.isEmpty()) lines.add(new String[]{key, text});
                    }
                    if (!lines.isEmpty()) exchanges.add(lines);
                }
                if (members.size() >= 2 && !exchanges.isEmpty()) pairs.add(new Pair(members, exchanges));
            } catch (Throwable ignored) { }
        }
        lastByPair = new long[pairs.size()];
    }

    public boolean enabled() { return enabled && !pairs.isEmpty(); }

    /** Tick periódico (cada ~5 s): mira si alguna pareja está junta y suelta una charla. */
    public void tick() {
        if (!enabled()) return;
        NPCRegistry reg;
        try { reg = CitizensAPI.getNPCRegistry(); } catch (Throwable t) { return; }
        if (reg == null) return;
        AiConfig c = cfg.get();
        long now = System.currentTimeMillis();
        for (int pi = 0; pi < pairs.size(); pi++) {
            try {
                if (now - lastByPair[pi] < cooldownMs) continue;
                Pair pair = pairs.get(pi);
                List<Entity> ents = new ArrayList<>();
                for (String key : pair.members()) {
                    Entity e = findEntity(reg, c, key);
                    if (e == null) break;
                    ents.add(e);
                }
                if (ents.size() != pair.members().size()) continue;   // falta algún miembro spawneado
                Entity base = ents.get(0);
                World w = base.getWorld();
                boolean ok = true;
                for (Entity e : ents) {
                    if (e.getWorld() != w || e.getLocation().distanceSquared(base.getLocation()) > range * range) { ok = false; break; }
                    if (mgr.isEntityBusy(e.getUniqueId())) { ok = false; break; }   // no interrumpir una charla con un jugador
                }
                if (!ok) continue;
                if (!playerNear(base, hearRadius)) continue;            // nadie cerca: no gastamos la charla
                if (ThreadLocalRandom.current().nextDouble() > chance) continue;
                lastByPair[pi] = now;
                List<String[]> exchange = pair.exchanges().get(ThreadLocalRandom.current().nextInt(pair.exchanges().size()));
                playExchange(reg, c, exchange);
            } catch (Throwable ignored) { }
        }
    }

    private void playExchange(NPCRegistry reg, AiConfig c, List<String[]> lines) {
        for (int i = 0; i < lines.size(); i++) {
            final String key = lines.get(i)[0];
            final String text = lines.get(i)[1];
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                try {
                    NpcPersona persona = c.personas.get(key);
                    if (persona == null) return;
                    Entity e = findEntity(reg, c, key);
                    if (e == null) return;
                    holo.show(e, persona.color + text, holoSeconds);
                    String line = c.replyFormat.replace("{color}", persona.color)
                            .replace("{npc}", persona.name).replace("{text}", text);
                    String legacy = ChatColor.translateAlternateColorCodes('&', line);
                    World w = e.getWorld();
                    Location at = e.getLocation();
                    for (Player p : w.getPlayers()) {
                        if (p.getLocation().distanceSquared(at) <= hearRadius * hearRadius)
                            p.sendMessage(LEGACY.deserialize(legacy));
                    }
                    blip(e, persona, c, text.length());
                } catch (Throwable ignored) { }
            }, (long) i * lineDelayTicks);
        }
    }

    /** Ráfaga de blips vanilla con el tono del personaje (igual que la "voz" de las charlas con jugadores). */
    private void blip(Entity npc, NpcPersona persona, AiConfig c, int textLen) {
        if (!c.voiceEnabled) return;
        World w = npc.getWorld();
        if (w == null) return;
        Location loc = npc.getLocation().add(0, npc.getHeight() * 0.8, 0);
        int blips = Math.max(3, Math.min(c.voiceMaxBlips, textLen / 12 + 2));
        double basePitch = persona.voicePitch > 0 ? persona.voicePitch : c.voiceBasePitch;
        String sound = (persona.voiceSound != null && !persona.voiceSound.isBlank()) ? persona.voiceSound : c.voiceSound;
        float vol = (float) c.voiceVolume;
        for (int i = 0; i < blips; i++) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                try {
                    float pitch = (float) Math.max(0.5, Math.min(2.0, basePitch + (ThreadLocalRandom.current().nextDouble() - 0.5) * 0.2));
                    w.playSound(loc, sound, vol, pitch);
                } catch (Throwable ignored) { }
            }, (long) i * 2L);
        }
    }

    /** Busca la entidad del NPC de Citizens cuyo persona casa con esta clave (spawneado). */
    private Entity findEntity(NPCRegistry reg, AiConfig c, String key) {
        for (NPC npc : reg) {
            if (npc == null || !npc.isSpawned()) continue;
            String n = npc.getName();
            NpcPersona p = c.match(n == null ? null : ChatColor.stripColor(n));
            if (p != null && p.key.equals(key)) return npc.getEntity();
        }
        return null;
    }

    private boolean playerNear(Entity e, double radius) {
        World w = e.getWorld();
        if (w == null) return false;
        Location at = e.getLocation();
        for (Player p : w.getPlayers()) {
            if (p.getLocation().distanceSquared(at) <= radius * radius) return true;
        }
        return false;
    }
}
