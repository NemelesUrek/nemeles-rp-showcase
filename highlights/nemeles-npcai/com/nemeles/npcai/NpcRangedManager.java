package com.nemeles.npcai;

import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Vector;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

/**
 * Ataque a DISTANCIA de los NPC hostiles (pistola hitscan vanilla). Lo invoca HostilityManager cuando el
 * objetivo esta FUERA del rango melee pero DENTRO del rango de tiro y la persona tiene "ranged: true".
 * Disparo instantaneo: rayo de particulas + sonido + dano directo, con probabilidad de acierto. Crossplay.
 */
public final class NpcRangedManager {

    private final Plugin plugin;
    private final Supplier<AiConfig> cfg;

    private final Map<String, Long> lastShot = new ConcurrentHashMap<>();

    public NpcRangedManager(Plugin plugin, Supplier<AiConfig> cfg) {
        this.plugin = plugin;
        this.cfg = cfg;
    }

    public boolean tryShoot(AiConfig c, Player p, Entity ent, NpcPersona persona, double dist) {
        if (c == null || persona == null || p == null || ent == null) return false;
        if (!persona.ranged) return false;
        if (dist > persona.rangedRange) return false;

        String k = ent.getUniqueId() + "|" + p.getUniqueId();
        long now = System.currentTimeMillis();
        Long last = lastShot.get(k);
        if (last != null && now - last < persona.rangedCooldownMs) return false;

        try { if (com.nemeles.core.api.NemelesApi.regions().isSafezone(p.getLocation())) return false; } catch (Throwable ignored) { }
        try { if (com.nemeles.core.api.NemelesApi.combat().isDowned(p.getUniqueId())) return false; } catch (Throwable ignored) { }

        lastShot.put(k, now);

        try {
            ent.lookAt(p.getEyeLocation().getX(), p.getEyeLocation().getY(), p.getEyeLocation().getZ(),
                    io.papermc.paper.entity.LookAnchor.EYES);
        } catch (Throwable ignored) { }
        if (ent instanceof Player npcPl) { try { npcPl.swingMainHand(); } catch (Throwable ignored) { } }

        Location from;
        try { from = ent.getLocation().add(0, ent.getHeight() * 0.85, 0); }
        catch (Throwable ignored) { from = ent.getLocation().add(0, 1.4, 0); }
        Location to = p.getEyeLocation();

        // Linea de vision: si hay un bloque solido entre el NPC y el jugador, la bala se clava en la pared
        // (no atraviesa muros). El rastro termina en el punto de impacto del bloque.
        boolean blocked = false;
        try {
            World w = from.getWorld();
            Vector ray = to.toVector().subtract(from.toVector());
            double rlen = ray.length();
            if (w != null && rlen > 0.001) {
                org.bukkit.util.RayTraceResult rt = w.rayTraceBlocks(
                        from, ray.clone().normalize(), rlen, org.bukkit.FluidCollisionMode.NEVER, true);
                if (rt != null && rt.getHitBlock() != null) {
                    blocked = true;
                    to = rt.getHitPosition().toLocation(w);
                }
            }
        } catch (Throwable ignored) { }

        boolean hit = !blocked && ThreadLocalRandom.current().nextDouble() < clamp01(persona.rangedAccuracy);

        playShotSound(c, from);
        drawTracer(from, blocked ? to : (hit ? to : missEnd(from, to)));
        spawnMuzzle(c, from);

        if (hit) {
            try {
                if (ent instanceof LivingEntity le) p.damage(persona.rangedDamage, le);
                else p.damage(persona.rangedDamage);
            } catch (Throwable ignored) { }
            try { p.getWorld().playSound(p.getLocation(), c.rangedImpactSound, 1f, 1f); } catch (Throwable ignored) { }
        }
        return true;
    }

    private void playShotSound(AiConfig c, Location at) {
        try { World w = at.getWorld(); if (w != null) w.playSound(at, c.rangedShotSound, 1.2f, 1.0f); }
        catch (Throwable ignored) { }
    }

    private void spawnMuzzle(AiConfig c, Location at) {
        try {
            World w = at.getWorld();
            if (w == null) return;
            Particle part;
            try { part = Particle.valueOf(c.rangedMuzzleParticle.toUpperCase(Locale.ROOT)); }
            catch (Throwable badName) { part = Particle.SMOKE; }   // nombre invalido en la config: humo por defecto
            w.spawnParticle(part, at, 4, 0.05, 0.05, 0.05, 0.01);
        } catch (Throwable ignored) { }
    }

    private void drawTracer(Location from, Location to) {
        try {
            World w = from.getWorld();
            if (w == null || to.getWorld() == null || !w.equals(to.getWorld())) return;
            AiConfig c = cfg.get();
            Particle part = Particle.valueOf(c.rangedTracerParticle.toUpperCase(Locale.ROOT));
            Vector dir = to.toVector().subtract(from.toVector());
            double len = dir.length();
            if (len < 0.001) return;
            dir.normalize();
            double step = 0.5;
            for (double d = 0.6; d < len; d += step) {
                Location pt = from.clone().add(dir.clone().multiply(d));
                w.spawnParticle(part, pt, 1, 0, 0, 0, 0);
            }
        } catch (Throwable ignored) { }
    }

    private Location missEnd(Location from, Location to) {
        try {
            Vector dir = to.toVector().subtract(from.toVector());
            double len = Math.max(1.0, dir.length());
            dir.normalize();
            double off = 0.6 + ThreadLocalRandom.current().nextDouble() * 0.8;
            Vector side = new Vector(-dir.getZ(), 0, dir.getX());
            if (side.lengthSquared() < 0.001) side = new Vector(1, 0, 0);
            side.normalize().multiply(ThreadLocalRandom.current().nextBoolean() ? off : -off);
            return from.clone().add(dir.multiply(len)).add(side).add(0, ThreadLocalRandom.current().nextDouble() * 0.6, 0);
        } catch (Throwable ignored) { return to; }
    }

    private static double clamp01(double v) { return v < 0 ? 0 : (v > 1 ? 1 : v); }
}
