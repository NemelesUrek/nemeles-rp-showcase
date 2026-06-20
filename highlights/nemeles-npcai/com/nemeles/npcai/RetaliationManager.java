package com.nemeles.npcai;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * "No se dejan hostigar". Centraliza la RETALIACION para que el golpe instantaneo (NpcDamageListener)
 * y el tick de proximidad (HostilityManager) usen LAS MISMAS reglas: un cooldown, una escalada, una racha.
 */
public final class RetaliationManager {

    private final Plugin plugin;
    private final Supplier<AiConfig> cfg;
    private final AffinityManager affinity;
    private final ConversationManager mgr;

    private final Map<String, Long> lastHit = new ConcurrentHashMap<>();
    private final Map<String, Streak> streaks = new ConcurrentHashMap<>();
    private final Map<String, Long> chaseUntil = new ConcurrentHashMap<>();
    private final Map<String, Long> engagedUntil = new ConcurrentHashMap<>();   // combate por incidente: (npc|jugador) -> hasta cuando

    private static final class Streak { int count; long last; }

    public RetaliationManager(Plugin plugin, Supplier<AiConfig> cfg, AffinityManager affinity, ConversationManager mgr) {
        this.plugin = plugin;
        this.cfg = cfg;
        this.affinity = affinity;
        this.mgr = mgr;
    }

    private static String key(Entity npc, Player p) { return npc.getUniqueId() + "|" + p.getUniqueId(); }

    public void onHarassed(Player attacker, Entity npc, NpcPersona persona, boolean hard) {
        AiConfig c = cfg.get();
        if (c == null || !c.hostilityEnabled || affinity == null) return;
        try {
            // un GOLPE rompe la charla: no puedes pegarle y seguir "conversando" (la charla le protegia de atacar).
            // Asi, al pegarle, deja de estar "hablando" y SI puede retaliar (melee o disparo en el siguiente tick).
            if (hard && mgr != null) { try { mgr.forceEndEntity(npc.getUniqueId()); } catch (Throwable ignored) { } }
            String k = key(npc, attacker);
            long now = System.currentTimeMillis();
            Streak s = streaks.computeIfAbsent(k, x -> new Streak());
            if (s.count > 0 && now - s.last > c.hostStreakDecayMs) s.count = 0;
            s.count += hard ? 2 : 1;
            s.last = now;

            int aff = affinity.get(persona.key, attacker.getUniqueId());
            boolean hostileNow = isHostile(c, aff, s.count);

            // un golpe FUERTE arranca/renueva el "combate por incidente": a partir de aqui el NPC SI puede
            // atacarte por su cuenta, pero SOLO durante esta ventana. Si dejas de pegarle, se desengancha.
            if (hard) engagedUntil.put(k, now + Math.max(2000L, c.hostEngageMs));

            if (hostileNow && s.count >= c.hostStreakHostile && c.hostChaseEnabled) {
                chaseUntil.put(k, now + c.hostChaseMs);
            }
            if (hostileNow || isEngaged(npc, attacker)) {
                double dist = safeDistance(npc, attacker);
                if (dist <= c.hostAttackRadius + 1.0) tryAttack(c, attacker, npc, persona, aff, s.count);
            }
        } catch (Throwable ignored) { }
    }

    public boolean isHostile(AiConfig c, int aff, int streak) {
        if (aff <= c.hostHostileThreshold) return true;
        return aff <= c.hostWaryThreshold && streak >= c.hostStreakHostile;
    }

    public int streak(Entity npc, Player p) {
        Streak s = streaks.get(key(npc, p));
        return s == null ? 0 : s.count;
    }

    public boolean isChasing(Entity npc, Player p) {
        Long until = chaseUntil.get(key(npc, p));
        return until != null && until > System.currentTimeMillis();
    }

    /** ¿Este NPC sigue "en combate" con este jugador (le pego hace poco)? Solo asi ataca por su cuenta. */
    public boolean isEngaged(Entity npc, Player p) {
        Long until = engagedUntil.get(key(npc, p));
        return until != null && until > System.currentTimeMillis();
    }

    /**
     * Inicia/renueva la persecucion de este NPC al jugador. Lo usa HostilityManager tambien cuando el odio
     * es por AFINIDAD pura (enemigo declarado, aff &lt;= hostile-threshold) sin necesidad de racha de golpes.
     */
    public void startChase(AiConfig c, Entity npc, Player p) {
        if (c == null || !c.hostChaseEnabled) return;
        try { chaseUntil.put(key(npc, p), System.currentTimeMillis() + c.hostChaseMs); } catch (Throwable ignored) { }
    }

    public boolean tryAttack(AiConfig c, Player p, Entity npc, NpcPersona persona, int aff, int streak) {
        if (p.getGameMode() == GameMode.CREATIVE || p.getGameMode() == GameMode.SPECTATOR) return false;
        if (p.hasPermission("nemeles.npcai.bypass")) return false;
        if (mgr != null && mgr.isTalkingTo(p.getUniqueId(), npc.getUniqueId())) return false;

        String k = key(npc, p);
        long now = System.currentTimeMillis();
        long cd = cooldownMs(c, aff, streak);
        Long last = lastHit.get(k);
        if (last != null && now - last < cd) return false;

        try { if (com.nemeles.core.api.NemelesApi.regions().isSafezone(p.getLocation())) return false; } catch (Throwable ignored) { }
        try { if (com.nemeles.core.api.NemelesApi.combat().isDowned(p.getUniqueId())) return false; } catch (Throwable ignored) { }

        lastHit.put(k, now);
        lookAt(npc, p);
        equipWeapon(npc, persona);
        if (npc instanceof Player npcPl) { try { npcPl.swingMainHand(); } catch (Throwable ignored) { } }

        double dmg = damageFor(c, aff, streak);
        try {
            if (npc instanceof LivingEntity le) p.damage(dmg, le);
            else p.damage(dmg);
        } catch (Throwable ignored) { }
        spawnAnger(npc, 10);
        try { p.playSound(p.getLocation(), c.hostAttackSound, 1f, attackPitch(streak)); } catch (Throwable ignored) { }

        if (c.hostChaseEnabled && isChasing(npc, p)) navigateTo(npc, p, c);
        return true;
    }

    /** Fuera del alcance de golpe pero enganchado/hostil: el NPC se ACERCA al jugador (persigue) para poder pegarle. */
    public void pursue(AiConfig c, Player p, Entity npc) {
        if (c == null || !c.hostChaseEnabled) return;
        if (p.getGameMode() == GameMode.CREATIVE || p.getGameMode() == GameMode.SPECTATOR) return;
        if (p.hasPermission("nemeles.npcai.bypass")) return;
        if (mgr != null && mgr.isTalkingTo(p.getUniqueId(), npc.getUniqueId())) return;
        try { if (com.nemeles.core.api.NemelesApi.regions().isSafezone(p.getLocation())) return; } catch (Throwable ignored) { }
        startChase(c, npc, p);
        navigateTo(npc, p, c);
    }

    private double damageFor(AiConfig c, int aff, int streak) {
        double dmg = c.hostDamage;
        if (c.hostEscalate) {
            int below = Math.max(0, c.hostHostileThreshold - aff);
            dmg += (below / 25.0) * c.hostEscalateDamageStep;
            dmg += Math.max(0, streak - c.hostStreakHostile) * c.hostEscalateStreakDamage;
        }
        return Math.max(1.0, Math.min(c.hostDamageMax, dmg));
    }

    private long cooldownMs(AiConfig c, int aff, int streak) {
        long cd = c.hostAttackCooldownMs;
        if (c.hostEscalate) {
            int below = Math.max(0, c.hostHostileThreshold - aff);
            cd -= (long) ((below / 25.0) * c.hostEscalateCooldownStepMs);
            cd -= (long) Math.max(0, streak - c.hostStreakHostile) * c.hostEscalateStreakCooldownMs;
        }
        return Math.max(c.hostAttackCooldownMinMs, cd);
    }

    private float attackPitch(int streak) { return (float) Math.min(1.6, 0.9 + streak * 0.05); }

    private void navigateTo(Entity npc, Player p, AiConfig c) {
        try {
            net.citizensnpcs.api.npc.NPC cit = net.citizensnpcs.api.CitizensAPI.getNPCRegistry().getNPC(npc);
            if (cit == null || !cit.isSpawned()) return;
            cit.getNavigator().getLocalParameters().speedModifier((float) c.hostChaseSpeed);
            cit.getNavigator().setTarget(p, true);
        } catch (Throwable ignored) { }
    }

    private void lookAt(Entity npc, Player p) {
        try {
            npc.lookAt(p.getEyeLocation().getX(), p.getEyeLocation().getY(), p.getEyeLocation().getZ(),
                    io.papermc.paper.entity.LookAnchor.EYES);
        } catch (Throwable ignored) { }
    }

    private void equipWeapon(Entity npc, NpcPersona persona) {
        if (persona.weapon == null || persona.weapon.isBlank()) return;
        try {
            org.bukkit.Material m = org.bukkit.Material.matchMaterial(persona.weapon.toUpperCase(Locale.ROOT));
            if (m == null) return;
            net.citizensnpcs.api.npc.NPC cit = net.citizensnpcs.api.CitizensAPI.getNPCRegistry().getNPC(npc);
            if (cit == null) return;
            net.citizensnpcs.api.trait.trait.Equipment eq = cit.getOrAddTrait(net.citizensnpcs.api.trait.trait.Equipment.class);
            org.bukkit.inventory.ItemStack cur = eq.get(net.citizensnpcs.api.trait.trait.Equipment.EquipmentSlot.HAND);
            if (cur == null || cur.getType() != m) {
                eq.set(net.citizensnpcs.api.trait.trait.Equipment.EquipmentSlot.HAND, new org.bukkit.inventory.ItemStack(m));
            }
        } catch (Throwable ignored) { }
    }

    private void spawnAnger(Entity npc, int count) {
        try {
            World w = npc.getWorld();
            Location at = npc.getLocation().add(0, npc.getHeight() + 0.4, 0);
            Particle part = Particle.valueOf(cfg.get().hostParticle.toUpperCase(Locale.ROOT));
            w.spawnParticle(part, at, count, 0.25, 0.2, 0.25, 0.02);
        } catch (Throwable ignored) { }
    }

    /** Calma TOTAL a este NPC: olvida rachas, persecucion, combate y cooldowns con todos los jugadores
     *  (al regenerar vida, al morir o al respawnear: el incidente termina y vuelve a ser neutral). */
    public void calm(Entity npc) {
        try {
            String prefix = npc.getUniqueId() + "|";
            streaks.keySet().removeIf(k -> k.startsWith(prefix));
            chaseUntil.keySet().removeIf(k -> { if (k.startsWith(prefix)) { stopChaseNav(k); return true; } return false; });
            lastHit.keySet().removeIf(k -> k.startsWith(prefix));
            engagedUntil.keySet().removeIf(k -> k.startsWith(prefix));
        } catch (Throwable ignored) { }
    }

    /** El JUGADOR murio (o queremos cortar): TODOS los NPCs se desenganchan de el. Asi al revivir nadie le ataca
     *  "en bucle"; solo volveran a pelear si el les vuelve a pegar. */
    public void calmPlayer(java.util.UUID playerId) {
        try {
            String suffix = "|" + playerId;
            streaks.keySet().removeIf(k -> k.endsWith(suffix));
            chaseUntil.keySet().removeIf(k -> { if (k.endsWith(suffix)) { stopChaseNav(k); return true; } return false; });
            lastHit.keySet().removeIf(k -> k.endsWith(suffix));
            engagedUntil.keySet().removeIf(k -> k.endsWith(suffix));
        } catch (Throwable ignored) { }
    }

    /** Cancela la navegacion de persecucion del NPC cuya key (npcUuid|playerUuid) ya expiro. */
    private void stopChaseNav(String k) {
        try {
            int bar = k.indexOf('|');
            if (bar <= 0) return;
            java.util.UUID npcId = java.util.UUID.fromString(k.substring(0, bar));
            Entity ent = org.bukkit.Bukkit.getEntity(npcId);
            if (ent == null) return;
            net.citizensnpcs.api.npc.NPC cit = net.citizensnpcs.api.CitizensAPI.getNPCRegistry().getNPC(ent);
            if (cit != null && cit.isSpawned() && cit.getNavigator().isNavigating())
                cit.getNavigator().cancelNavigation();
        } catch (Throwable ignored) { }
    }

    private double safeDistance(Entity npc, Player p) {
        try {
            if (!npc.getWorld().equals(p.getWorld())) return Double.MAX_VALUE;
            return npc.getLocation().distance(p.getLocation());
        } catch (Throwable t) { return Double.MAX_VALUE; }
    }

    public void sweep() {
        try {
            AiConfig c = cfg.get();
            long now = System.currentTimeMillis();
            long decay = (c == null) ? 8000L : c.hostStreakDecayMs;
            streaks.entrySet().removeIf(e -> now - e.getValue().last > decay * 3);
            chaseUntil.entrySet().removeIf(e -> {
                if (e.getValue() >= now) return false;
                stopChaseNav(e.getKey());   // la persecucion expiro: el NPC deja de seguir (cancela navegacion)
                return true;
            });
            // el combate por incidente caduca: pasada la ventana sin golpes nuevos, el NPC se desengancha (y deja de seguir).
            engagedUntil.entrySet().removeIf(e -> {
                if (e.getValue() >= now) return false;
                stopChaseNav(e.getKey());
                return true;
            });
            lastHit.entrySet().removeIf(e -> now - e.getValue() > 60_000L);
        } catch (Throwable ignored) { }
    }
}
