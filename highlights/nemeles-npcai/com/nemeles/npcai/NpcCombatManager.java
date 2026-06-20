package com.nemeles.npcai;

import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.npc.NPCRegistry;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Hace que NUESTROS NPCs (Citizens EntityType.PLAYER que casan con un persona) se sientan jugadores reales:
 * son DANABLES (no protegidos) con una vida fija (npc-health, por defecto 50), y al morir NO sueltan loot ni
 * mensaje feo: caen y reaparecen en su "casa" (dato nemeles-home) tras unos segundos, recuperando vida.
 * Crossplay y a prueba de fallos: si Citizens no esta, todo es no-op. Solo API vanilla + datos de Citizens.
 */
public final class NpcCombatManager {

    private static final String HOME_KEY = "nemeles-home";   // MISMA clave que NpcLifeManager
    private static final String HEALTHED_KEY = "nemeles-health-applied";

    private final Plugin plugin;
    private final ConversationManager mgr;
    private final AffinityManager affinity;   // puede ser null
    private final Supplier<AiConfig> cfg;

    private final Map<Integer, Long> dying = new ConcurrentHashMap<>();
    private final Map<Integer, Long> lastHit = new ConcurrentHashMap<>();   // npcId -> ultimo golpe recibido (para el regen)
    private NpcArmorManager armor;            // opcional: para que el NPC respawnee SIN la armadura de batalla
    private RetaliationManager retaliation;   // opcional: para CALMARLO (olvidar racha/persecucion) al regenerar

    public NpcCombatManager(Plugin plugin, ConversationManager mgr, AffinityManager affinity, Supplier<AiConfig> cfg) {
        this.plugin = plugin;
        this.mgr = mgr;
        this.affinity = affinity;
        this.cfg = cfg;
    }

    /** P2: al morir/respawnear/regenerar, el NPC se quita la armadura defensiva (vuelve "en paz"). */
    public void setArmor(NpcArmorManager armor) { this.armor = armor; }

    /** Para calmar al NPC (olvidar racha y persecucion) cuando regenera la vida al alejarse el jugador. */
    public void setRetaliation(RetaliationManager retaliation) { this.retaliation = retaliation; }

    /** Marca que este NPC acaba de recibir dano (lo llama el listener de golpes). Reinicia el reloj del regen. */
    public void markHit(Entity victim) {
        try {
            NPC npc = CitizensAPI.getNPCRegistry().getNPC(victim);
            if (npc != null) lastHit.put(npc.getId(), System.currentTimeMillis());
        } catch (Throwable ignored) { }
    }

    /**
     * Regen: si un NPC esta herido, hace rato que no le pegan Y no hay ningun jugador cerca (se alejaron),
     * se CURA toda la vida, se quita la armadura de batalla y se calma (olvida racha/persecucion). Tick periodico.
     */
    public void regenTick() {
        AiConfig c = cfg.get();
        if (c == null || !c.npcDamageEnabled) return;
        NPCRegistry reg = registry();
        if (reg == null) return;
        long now = System.currentTimeMillis();
        for (NPC npc : reg) {
            try {
                if (npc == null || !npc.isSpawned()) continue;
                String name = npc.getName();
                if (name == null || c.match(ChatColor.stripColor(name)) == null) continue;
                Entity e = npc.getEntity();
                if (!(e instanceof LivingEntity le)) continue;
                double max = c.npcHealth;
                if (le.getHealth() >= max - 0.01) continue;            // ya esta a tope
                Long hit = lastHit.get(npc.getId());
                // SOLO se cura si lleva el tiempo completo (p.ej. 2 min) SIN recibir dano. NO mira distancia:
                // mientras le sigas pegando (cada golpe reinicia el reloj) nunca se cura, aunque te alejes.
                if (hit != null && now - hit < c.npcRegenDelayMs) continue;
                // paso el tiempo sin que lo toquen: se cura TODA la vida y se calma.
                applyHealth(le, max);
                if (armor != null) { try { armor.disarm(e); } catch (Throwable ignored) { } }
                if (retaliation != null) { try { retaliation.calm(e); } catch (Throwable ignored) { } }
                lastHit.remove(npc.getId());
                try { le.getWorld().spawnParticle(org.bukkit.Particle.HEART,
                        le.getLocation().add(0, le.getHeight() + 0.4, 0), 5, 0.25, 0.2, 0.25, 0.01); } catch (Throwable ignored) { }
            } catch (Throwable ignored) { }
        }
    }

    /** Pacifica a TODOS los NPCs spawneados: cura a tope + quita armadura + olvida racha/persecucion. Admin. */
    public int pacifyAll() {
        AiConfig c = cfg.get();
        if (c == null) return 0;
        NPCRegistry reg = registry();
        if (reg == null) return 0;
        int n = 0;
        for (NPC npc : reg) {
            try {
                if (npc == null || !npc.isSpawned()) continue;
                String name = npc.getName();
                if (name == null || c.match(ChatColor.stripColor(name)) == null) continue;
                Entity e = npc.getEntity();
                if (e instanceof LivingEntity le) applyHealth(le, c.npcHealth);
                if (armor != null) { try { armor.disarm(e); } catch (Throwable ignored) { } }
                if (retaliation != null) { try { retaliation.calm(e); } catch (Throwable ignored) { } }
                lastHit.remove(npc.getId());
                n++;
            } catch (Throwable ignored) { }
        }
        return n;
    }

    /** setProtected(false) + fija vida maxima/actual. A prueba de fallos. */
    public void makeVulnerable(NPC npc) {
        AiConfig c = cfg.get();
        if (c == null || !c.npcDamageEnabled) return;
        try { npc.setProtected(false); } catch (Throwable ignored) { }
        try {
            Entity e = npc.getEntity();
            if (e instanceof LivingEntity le) applyHealth(le, c.npcHealth);
            npc.data().setPersistent(HEALTHED_KEY, true);
        } catch (Throwable ignored) { }
    }

    private void applyHealth(LivingEntity le, double max) {
        try {
            var attr = le.getAttribute(maxHealthAttribute());
            if (attr != null) attr.setBaseValue(max);
        } catch (Throwable ignored) { }
        try {
            var a = le.getAttribute(maxHealthAttribute());
            double m = a != null ? a.getValue() : max;
            le.setHealth(Math.min(m, max));
        } catch (Throwable ignored) {
            try { le.setHealth(max); } catch (Throwable ignored2) { }
        }
    }

    @SuppressWarnings("deprecation")
    private static Attribute maxHealthAttribute() {
        try { return Attribute.valueOf("MAX_HEALTH"); } catch (Throwable ignored) { }
        try { return Attribute.valueOf("GENERIC_MAX_HEALTH"); } catch (Throwable ignored) { }
        return Attribute.MAX_HEALTH;
    }

    /** Barrido: hace vulnerables y fija vida a TODOS los NPCs ya spawneados que casan con un persona. */
    public int sweepExisting() {
        AiConfig c = cfg.get();
        if (c == null || !c.npcDamageEnabled) return 0;
        NPCRegistry reg = registry();
        if (reg == null) return 0;
        int n = 0;
        for (NPC npc : reg) {
            try {
                if (npc == null) continue;
                String name = npc.getName();
                if (name == null || c.match(ChatColor.stripColor(name)) == null) continue;
                makeVulnerable(npc);
                n++;
            } catch (Throwable ignored) { }
        }
        return n;
    }

    /**
     * Llamado desde NpcDamageListener cuando un golpe DEJARIA al NPC a 0. Gestiona la muerte limpia
     * (sin loot/broadcast) + respawn. Devuelve true si tomamos el control (el listener cancela el evento).
     */
    public boolean handleLethal(NPC npc, Player killer) {
        AiConfig c = cfg.get();
        if (c == null || !c.npcDamageEnabled) return false;
        int id = npc.getId();
        long now = System.currentTimeMillis();
        Long t = dying.get(id);
        if (t != null && now - t < 5000) return true;
        dying.put(id, now);

        try {
            Entity e = npc.getEntity();
            if (e != null && safezone(e.getLocation())) {
                if (e instanceof LivingEntity le) { try { le.setHealth(1.0); } catch (Throwable ignored) { } }
                dying.remove(id);
                return true;   // en safezona no muere: tragamos el golpe letal
            }
        } catch (Throwable ignored) { }

        try {
            Entity e = npc.getEntity();
            Location at = (e != null) ? e.getLocation() : null;
            if (at != null) {
                try { at.getWorld().playSound(at, "entity.player.death", 1f, 1f); } catch (Throwable ignored) { }
                try {
                    org.bukkit.Particle smoke = org.bukkit.Particle.valueOf("LARGE_SMOKE");
                    at.getWorld().spawnParticle(smoke, at.clone().add(0, 1, 0), 12, 0.25, 0.4, 0.25, 0.01);
                } catch (Throwable ignored) { }
            }
            // el incidente termina con su muerte: olvida racha/persecucion/combate (no revive "enganchado").
            if (retaliation != null && e != null) { try { retaliation.calm(e); } catch (Throwable ignored) { } }
            try { if (e != null) mgr.forceEndEntity(e.getUniqueId()); } catch (Throwable ignored) { }
            try { npc.despawn(); } catch (Throwable ignored) { }
        } catch (Throwable ignored) { }

        if (c.npcResetAffinityOnDeath && affinity != null && killer != null) {
            try {
                String name = npc.getName();
                NpcPersona persona = (name == null) ? null : c.match(ChatColor.stripColor(name));
                if (persona != null) affinity.set(persona.key, killer.getUniqueId(), 0);
            } catch (Throwable ignored) { }
        }

        long ticks = Math.max(40L, c.npcRespawnSeconds * 20L);
        Bukkit.getScheduler().runTaskLater(plugin, () -> respawnHome(id), ticks);
        return true;
    }

    private void respawnHome(int id) {
        try {
            NPCRegistry reg = registry();
            if (reg == null) { dying.remove(id); return; }
            NPC npc = reg.getById(id);
            if (npc == null) { dying.remove(id); return; }

            Location home = parseLoc(safeData(npc, HOME_KEY));
            if (home == null) home = npc.getStoredLocation();
            if (home == null || home.getWorld() == null) { dying.remove(id); return; }

            if (!npc.isSpawned()) { try { npc.spawn(home); } catch (Throwable ignored) { } }
            else { try { npc.teleport(home, org.bukkit.event.player.PlayerTeleportEvent.TeleportCause.PLUGIN); } catch (Throwable ignored) { } }

            // makeVulnerable ya fija vida maxima + vida actual con clamp (applyHealth con Math.min): no repetir
            // un setHealth sin clamp aqui (lanzaria IllegalArgumentException si MAX_HEALTH aun fuera 20).
            makeVulnerable(npc);
            // vuelve "en paz": se quita la armadura de batalla (si lo vuelven a atacar, se la pone otra vez).
            if (armor != null) { try { armor.disarm(npc.getEntity()); } catch (Throwable ignored) { } }
        } catch (Throwable ignored) {
        } finally {
            dying.remove(id);
        }
    }

    private boolean safezone(Location loc) {
        try { return com.nemeles.core.api.NemelesApi.regions().isSafezone(loc); }
        catch (Throwable ignored) { return false; }
    }

    private static String safeData(NPC npc, String key) {
        try { return npc.data().has(key) ? String.valueOf(npc.data().get(key)) : null; }
        catch (Throwable ignored) { return null; }
    }

    private NPCRegistry registry() {
        try { return CitizensAPI.getNPCRegistry(); }
        catch (Throwable t) { return null; }
    }

    private static Location parseLoc(String s) {
        if (s == null) return null;
        String[] p = s.split(";");
        if (p.length != 4) return null;
        World w = Bukkit.getWorld(p[0]);
        if (w == null) return null;
        try {
            return new Location(w, Integer.parseInt(p[1]) + 0.5, Integer.parseInt(p[2]), Integer.parseInt(p[3]) + 0.5);
        } catch (NumberFormatException e) { return null; }
    }
}
