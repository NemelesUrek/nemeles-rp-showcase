package com.nemeles.npcai;

import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.npc.NPCRegistry;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

/**
 * Da VIDA a los NPCs de Citizens que casan con un persona: pasean por el mundo como vecinos
 * (deambulan alrededor de su "casa", con correa para no perderse, y se paran si hablas con ellos).
 * También crea el ROSTER completo (un NPC por cada persona) repartido por el mundo.
 *
 * Usa solo API estable de Citizens (Navigator + data persistente). A prueba de fallos: si Citizens
 * no está o algo peta, no rompe nada (el resto del plugin sigue).
 */
public final class NpcLifeManager {

    private static final String HOME_KEY = "nemeles-home";   // dato persistente del NPC: su casa "world;x;y;z"

    /** Protagonistas con IA (los que el jugador trata primero). Usado por /npcai protas y por la armadura
     *  defensiva por defecto (armor.npcs vacio). Son los 5: Simon, Maid, Miss, Chibi, Luna.
     *  (Ramon, Nico, Lola y Ryu son secundarios: NO entran aqui por decision del usuario.) */
    public static final java.util.Set<String> PROTAGONISTS = java.util.Set.of(
            "simon", "maid", "miss", "chibi", "luna");

    private final Plugin plugin;
    private final ConversationManager mgr;
    private final Supplier<AiConfig> cfg;
    private java.util.function.IntPredicate followingCheck = id -> false;   // ¿este NPC esta escoltando a alguien?
    private NpcCombatManager combat;   // hace vulnerables a los NPCs y gestiona su muerte/respawn (null = off)

    /** Inyecta el gestor de combate para que los NPCs nazcan danables (con vida fija). */
    public void setCombat(NpcCombatManager combat) { this.combat = combat; }

    public NpcLifeManager(Plugin plugin, ConversationManager mgr, Supplier<AiConfig> cfg) {
        this.plugin = plugin;
        this.mgr = mgr;
        this.cfg = cfg;
    }

    /** El paseo no toca a los NPCs que estan SIGUIENDO a un jugador (lo gestiona InteractionManager). */
    public void setFollowingCheck(java.util.function.IntPredicate check) { this.followingCheck = check; }

    // ───────────────────────────── PASEO (tick) ─────────────────────────────

    /** Tick periódico: cada NPC nuestro decide si pasea, si vuelve a casa, o si se para a hablar. */
    public void tick() {
        AiConfig c = cfg.get();
        if (!c.lifeEnabled) return;
        NPCRegistry reg = registry();
        if (reg == null) return;
        double radius = c.wanderRadius, maxStray = c.maxStray, chance = c.wanderChance, speed = c.lifeSpeed;
        for (NPC npc : reg) {
            try {
                if (npc == null || !npc.isSpawned()) continue;
                String name = npc.getName();
                NpcPersona persona = c.match(name == null ? null : ChatColor.stripColor(name));
                if (persona == null) continue;                 // solo gestionamos NUESTROS NPCs
                Entity e = npc.getEntity();
                if (e == null) continue;
                if (followingCheck.test(npc.getId())) continue;   // esta escoltando a un jugador: no pasea ni vuelve a casa

                // ¿Alguien está hablando con él? -> se queda quieto y atento.
                if (c.pauseOnTalk && mgr.isEntityBusy(e.getUniqueId())) {
                    if (npc.getNavigator().isNavigating()) npc.getNavigator().cancelNavigation();
                    continue;
                }
                if (npc.getNavigator().isNavigating()) continue;   // ya va andando

                Location home = homeOf(npc, e.getLocation());
                Location cur = e.getLocation();
                boolean farFromHome = home.getWorld() != null && home.getWorld().equals(cur.getWorld())
                        && cur.distanceSquared(home) > maxStray * maxStray;

                if (!farFromHome && ThreadLocalRandom.current().nextDouble() > chance) continue;

                Location target = farFromHome ? home : randomNear(home, radius);
                if (target == null) continue;
                try { npc.getNavigator().getLocalParameters().speedModifier((float) speed); } catch (Throwable ignored) { }
                npc.getNavigator().setTarget(target);
            } catch (Throwable ignored) { /* un NPC roto no debe parar al resto */ }
        }
    }

    /** Casa del NPC: dato persistente; si no lo tiene, la fija a su posición actual la primera vez. */
    private Location homeOf(NPC npc, Location fallback) {
        try {
            if (npc.data().has(HOME_KEY)) {
                Location l = parseLoc(npc.data().get(HOME_KEY));
                if (l != null) return l;
            }
            npc.data().setPersistent(HOME_KEY, locToStr(fallback));
        } catch (Throwable ignored) { }
        return fallback;
    }

    /** Punto de paseo aleatorio cerca de 'home', sobre suelo firme y en chunk cargado (o null si no vale). */
    private Location randomNear(Location home, double radius) {
        World w = home.getWorld();
        if (w == null) return null;
        ThreadLocalRandom r = ThreadLocalRandom.current();
        int x = home.getBlockX() + r.nextInt((int) (-radius), (int) radius + 1);
        int z = home.getBlockZ() + r.nextInt((int) (-radius), (int) radius + 1);
        if (!w.isChunkLoaded(x >> 4, z >> 4)) return null;          // no forzamos carga de chunks
        int y = w.getHighestBlockYAt(x, z);
        Block top = w.getBlockAt(x, y, z);
        if (top.isLiquid()) return null;                             // no le mandamos al agua
        return new Location(w, x + 0.5, y + 1, z + 0.5);
    }

    // ───────────────────────────── ROSTER (crear los 50) ─────────────────────────────

    /**
     * Crea un NPC de Citizens por cada persona que TODAVÍA no exista en el mundo, repartidos en espiral
     * alrededor del que ejecuta el comando. Les pone nombre con color, casa, skin (si la persona la define)
     * y arranca su paseo. Idempotente: no duplica los que ya existen.
     */
    public int spawnRoster(Player sender) { return spawnFiltered(sender, p -> true); }

    /** Crea SOLO a los cinco protagonistas (los que falten), alrededor del jugador. */
    public int spawnProtagonists(Player sender) { return spawnFiltered(sender, p -> PROTAGONISTS.contains(p.key)); }

    private int spawnFiltered(Player sender, java.util.function.Predicate<NpcPersona> keep) {
        AiConfig c = cfg.get();
        NPCRegistry reg = registry();
        if (reg == null) { sender.sendMessage("§cCitizens no está disponible: no puedo crear los NPCs."); return 0; }

        // Nombres (sin color) ya presentes en el mundo, para no duplicar.
        java.util.Set<String> present = new java.util.HashSet<>();
        for (NPC npc : reg) {
            String n = npc.getName();
            if (n != null) present.add(AiConfig.normalize(ChatColor.stripColor(n)));
        }

        Location center = sender.getLocation();
        World w = center.getWorld();
        if (w == null) return 0;
        List<NpcPersona> toCreate = new ArrayList<>();
        for (NpcPersona p : c.personas.values()) {
            if (!keep.test(p)) continue;
            if (!present.contains(AiConfig.normalize(p.name)) && !present.contains(AiConfig.normalize(p.key))) toCreate.add(p);
        }

        int i = 0, created = 0, stagger = 0;
        for (NpcPersona p : toCreate) {
            try {
                // Reparto en espiral filotáctica (ángulo áureo) para que no se amontonen.
                double ang = i * 2.399963229728653;
                double rad = 3.0 + 1.7 * Math.sqrt(i + 1);
                int x = center.getBlockX() + (int) Math.round(Math.cos(ang) * rad);
                int z = center.getBlockZ() + (int) Math.round(Math.sin(ang) * rad);
                int y = w.getHighestBlockYAt(x, z) + 1;
                Location loc = new Location(w, x + 0.5, y, z + 0.5);

                String display = ChatColor.translateAlternateColorCodes('&', p.color + p.name);
                NPC npc = reg.createNPC(EntityType.PLAYER, display);
                npc.spawn(loc);
                try { npc.data().setPersistent(HOME_KEY, locToStr(loc)); } catch (Throwable ignored) { }
                // Nace DANABLE con vida fija (tras un tick, para que ya exista su entidad viva).
                if (combat != null) {
                    final NPC ref = npc;
                    Bukkit.getScheduler().runTaskLater(plugin, () -> { try { combat.makeVulnerable(ref); } catch (Throwable ignored) { } }, 8L);
                }
                if (p.skinUrl != null && !p.skinUrl.isBlank()) {
                    dispatchSkin(npc.getId(), p.skinUrl, 12L + (stagger++ * 14L));
                } else {
                    // sin skin definida: una ALEATORIA del pool para que no parezcan bots clonados
                    dispatchSkinByName(npc.getId(), RANDOM_SKINS[java.util.concurrent.ThreadLocalRandom.current()
                            .nextInt(RANDOM_SKINS.length)], 12L + (stagger++ * 14L));
                }
                created++;
            } catch (Throwable t) {
                plugin.getLogger().warning("No se pudo crear el NPC '" + p.name + "': " + t.getMessage());
            }
            i++;
        }
        return created;
    }

    /** Borra los NPCs de Citizens cuyo nombre casa con un persona nuestro (no toca los demás). */
    public int clearRoster() {
        NPCRegistry reg = registry();
        if (reg == null) return 0;
        AiConfig c = cfg.get();
        List<NPC> doomed = new ArrayList<>();
        for (NPC npc : reg) {
            String n = npc.getName();
            if (n != null && c.match(ChatColor.stripColor(n)) != null) doomed.add(npc);
        }
        int n = 0;
        for (NPC npc : doomed) { try { npc.destroy(); n++; } catch (Throwable ignored) { } }
        return n;
    }

    /** Borra SOLO a los cinco protagonistas (para re-spawnearlos con skins/datos nuevos). No toca al resto. */
    public int clearProtagonists() {
        NPCRegistry reg = registry();
        if (reg == null) return 0;
        AiConfig c = cfg.get();
        List<NPC> doomed = new ArrayList<>();
        for (NPC npc : reg) {
            String n = npc.getName();
            if (n == null) continue;
            NpcPersona p = c.match(ChatColor.stripColor(n));
            if (p != null && PROTAGONISTS.contains(p.key)) doomed.add(npc);
        }
        int k = 0;
        for (NPC npc : doomed) { try { npc.destroy(); k++; } catch (Throwable ignored) { } }
        return k;
    }

    // ───────────────────────────── utilidades ─────────────────────────────

    private NPCRegistry registry() {
        try { return CitizensAPI.getNPCRegistry(); }
        catch (Throwable t) { return null; }
    }

    /** Pool de skins variadas (nombres premium conocidos: Citizens/SkinsRestorer resuelven la textura). */
    private static final String[] RANDOM_SKINS = {
            "Rubius", "Vegetta777", "Willyrex", "AuronPlay", "Fernanfloo", "Luzu", "Mangel",
            "sTaXx", "Alexby11", "TheGrefg", "Dream", "Sapnap", "GeorgeNotFound", "Technoblade",
            "Philza", "CaptainSparklez", "Notch", "jeb_", "Dinnerbone", "Grian"
    };

    /** Aplica la skin de un JUGADOR conocido por nombre (para los NPC sin skin propia). */
    private void dispatchSkinByName(int id, String playerName, long delayTicks) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            try {
                CommandSender con = Bukkit.getConsoleSender();
                Bukkit.dispatchCommand(con, "npc select " + id);
                Bukkit.dispatchCommand(con, "npc skin " + playerName);
            } catch (Throwable ignored) { }
        }, delayTicks);
    }

    /** Replica seleccionar el NPC y aplicarle la skin por URL (modo offline), con retardo escalonado. */
    private void dispatchSkin(int id, String url, long delayTicks) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            try {
                CommandSender con = Bukkit.getConsoleSender();
                Bukkit.dispatchCommand(con, "npc select " + id);
                Bukkit.dispatchCommand(con, "npc skin --url " + url);
            } catch (Throwable ignored) { }
        }, delayTicks);
    }

    private static String locToStr(Location l) {
        return (l.getWorld() == null ? "world" : l.getWorld().getName())
                + ";" + l.getBlockX() + ";" + l.getBlockY() + ";" + l.getBlockZ();
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
