package com.nemeles.npcai;

import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
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
 * Hostilidad de NPCs por afinidad. Si tu afinidad invisible con un NPC cae lo suficiente, el NPC
 * te trata como enemigo: te AVISA si rondas su zona y te ATACA si te acercas demasiado a el.
 * Tick por proximidad (no listeners de movimiento). Todo crossplay (daño + sonido + particula vanilla)
 * y a prueba de fallos: respeta safezona y no golpea mientras estas en una charla con ese NPC
 * (esa es la via para reconciliarte: hablarle y recuperar su confianza).
 */
public final class HostilityManager {

    private static final LegacyComponentSerializer AMP = LegacyComponentSerializer.legacyAmpersand();

    private final Plugin plugin;
    private final Supplier<AiConfig> cfg;
    private final AffinityManager affinity;
    private final ConversationManager mgr;
    private final RetaliationManager retaliation;   // P3: cooldown/escalada/racha compartidos
    private NpcRangedManager ranged;                // P4: ataque a distancia (null = solo melee)
    private BondManager bond;                       // pase libre del alma gemela (null = sin easter egg)

    // throttle por (entidad|jugador) para no spamear avisos
    private final Map<String, Long> lastWarn = new ConcurrentHashMap<>();

    public HostilityManager(Plugin plugin, Supplier<AiConfig> cfg, AffinityManager affinity,
                            ConversationManager mgr, RetaliationManager retaliation) {
        this.plugin = plugin;
        this.cfg = cfg;
        this.affinity = affinity;
        this.mgr = mgr;
        this.retaliation = retaliation;
    }

    /** Inyecta el modulo de tiro a distancia (opcional). Sin el, el NPC solo pelea cuerpo a cuerpo. */
    public void setRanged(NpcRangedManager ranged) { this.ranged = ranged; }

    /** Inyecta el vinculo de alma gemela (opcional): su dueño tiene PASE LIBRE con Luna y los suyos. */
    public void setBond(BondManager bond) { this.bond = bond; }

    public void tick() {
        AiConfig c = cfg.get();
        if (c == null || !c.hostilityEnabled || affinity == null) return;
        double scan = c.hostScanRadius;
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getGameMode() == GameMode.CREATIVE || p.getGameMode() == GameMode.SPECTATOR) continue;
            if (p.hasPermission("nemeles.npcai.bypass")) continue;
            for (Entity ent : p.getNearbyEntities(scan, scan, scan)) {
                String raw = ent.getName();
                NpcPersona persona = c.match(raw == null ? null : ChatColor.stripColor(raw));
                if (persona == null) continue;
                // PASE LIBRE del alma gemela: Luna y los suyos NUNCA atacan/avisan a su dueño.
                if (bond != null && bond.isOwner(p.getUniqueId())
                        && (persona.key.equals(bond.persona()) || c.bond.allies.contains(persona.key))) continue;
                // calienta la cache de afinidad para este (persona, jugador) y lee el valor
                affinity.load(persona.key, p.getUniqueId());
                int aff = affinity.get(persona.key, p.getUniqueId());
                int streak = retaliation.streak(ent, p);
                // COMBATE POR INCIDENTE: el NPC solo te ATACA por su cuenta si esta "en combate" contigo (le pegaste
                // hace poco) o si esta malherido en plena pelea. La afinidad baja YA NO provoca ataques al verte
                // (asi no te atacan en bucle al respawnear ni por rencor viejo): a lo sumo te MIRAN/AVISAN.
                boolean engagedCombat = retaliation.isEngaged(ent, p);
                boolean engagedHealth = isLowHealth(ent, c);
                boolean fight = engagedCombat || engagedHealth;
                // ni en combate ni con desconfianza suficiente para mirarte mal: nada que hacer
                if (!fight && aff > c.hostWaryThreshold) continue;
                double dist = ent.getLocation().distance(p.getLocation());
                double atkR = c.hostAttackRadius + (retaliation.isChasing(ent, p) ? c.hostChaseReach : 0.0);
                if (fight && dist <= atkR) {
                    // via de reconciliacion: si charlas con ESTE NPC, no te golpea
                    if (mgr.isTalkingTo(p.getUniqueId(), ent.getUniqueId())) { warn(c, p, ent, persona); continue; }
                    // en plena pelea: que tambien te PERSIGA, no solo te pegue
                    retaliation.startChase(c, ent, p);
                    boolean melee = retaliation.tryAttack(c, p, ent, persona, aff, streak);
                    // si el golpe esta en cooldown pero estas FUERA del melee real y a tiro: Luna dispara de respaldo
                    if (!melee && persona.ranged && ranged != null
                            && dist > c.hostAttackRadius && dist <= persona.rangedRange) {
                        boolean shot = false;
                        try { shot = ranged.tryShoot(c, p, ent, persona, dist); } catch (Throwable ignored) { }
                        if (!shot && dist <= c.hostWarnRadius) warn(c, p, ent, persona);
                    }
                } else if (fight && persona.ranged && ranged != null && dist <= persona.rangedRange) {
                    // fuera del melee pero a tiro (P4): dispara (97% acierto en Luna). Charlando = no dispara.
                    if (mgr.isTalkingTo(p.getUniqueId(), ent.getUniqueId())) { warn(c, p, ent, persona); continue; }
                    boolean shot = false;
                    try { shot = ranged.tryShoot(c, p, ent, persona, dist); } catch (Throwable ignored) { }
                    if (!shot && dist <= c.hostWarnRadius) warn(c, p, ent, persona);
                } else if (fight) {
                    // enganchado/hostil pero FUERA de alcance (ni melee ni tiro): se ACERCA para alcanzarte.
                    retaliation.pursue(c, p, ent);
                    if (dist <= c.hostWarnRadius) warn(c, p, ent, persona);
                } else if (dist <= c.hostWarnRadius) {
                    warn(c, p, ent, persona);
                }
            }
        }
    }

    /** ¿El NPC ya esta herido por debajo del umbral (p.ej. 40 de 50)? Entonces sigue peleando hasta curarse/respawnear. */
    private boolean isLowHealth(Entity ent, AiConfig c) {
        if (!(ent instanceof LivingEntity le)) return false;
        try { return le.getHealth() <= c.npcHealth * c.npcAttackHealthPercent; }
        catch (Throwable t) { return false; }
    }

    private void warn(AiConfig c, Player p, Entity ent, NpcPersona persona) {
        String k = ent.getUniqueId() + "|" + p.getUniqueId();
        long now = System.currentTimeMillis();
        Long last = lastWarn.get(k);
        if (last != null && now - last < c.hostWarnCooldownMs) return;
        lastWarn.put(k, now);
        try { ent.lookAt(p.getEyeLocation().getX(), p.getEyeLocation().getY(), p.getEyeLocation().getZ(),
                io.papermc.paper.entity.LookAnchor.EYES); } catch (Throwable ignored) { }
        String msg = c.hostWarnMessage.replace("{npc}", persona.name);
        p.sendMessage(AMP.deserialize(ChatColor.translateAlternateColorCodes('&', msg)));
        spawnAnger(ent, 6);
        try { p.playSound(ent.getLocation(), c.hostWarnSound, 1f, 0.8f); } catch (Throwable ignored) { }
    }

    private void spawnAnger(Entity ent, int count) {
        try {
            World w = ent.getWorld();
            Location at = ent.getLocation().add(0, ent.getHeight() + 0.4, 0);
            Particle part = Particle.valueOf(cfg.get().hostParticle.toUpperCase(Locale.ROOT));
            w.spawnParticle(part, at, count, 0.25, 0.2, 0.25, 0.02);
        } catch (Throwable ignored) { }
    }
}
