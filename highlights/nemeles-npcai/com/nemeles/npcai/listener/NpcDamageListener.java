package com.nemeles.npcai.listener;

import com.nemeles.npcai.AffinityManager;
import com.nemeles.npcai.AiConfig;
import com.nemeles.npcai.NpcArmorManager;
import com.nemeles.npcai.NpcCombatManager;
import com.nemeles.npcai.NpcPersona;
import com.nemeles.npcai.RetaliationManager;
import org.bukkit.ChatColor;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.projectiles.ProjectileSource;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/** Golpear (o dispararle) a un NPC: baja afinidad, lo enfurece, se defiende (armadura) y, si es letal, muere/respawnea. */
public final class NpcDamageListener implements Listener {

    private final Supplier<AiConfig> cfg;
    private final AffinityManager affinity;                 // puede ser null (BD apagada)
    private final NpcCombatManager combat;                  // P1: muerte/respawn (null = off)
    private final NpcArmorManager armor;                    // P2: armadura (null = sin Citizens/off)
    private final RetaliationManager retaliation;           // P3: retaliacion instantanea (null = sin afinidad)
    private com.nemeles.npcai.BondManager bond;             // alma gemela: el dueño NO puede dañar a su NPC (null = sin easter egg)
    private final Map<UUID, Long> lastTold = new ConcurrentHashMap<>();

    public NpcDamageListener(Supplier<AiConfig> cfg, AffinityManager affinity,
                             NpcCombatManager combat, NpcArmorManager armor, RetaliationManager retaliation) {
        this.cfg = cfg;
        this.affinity = affinity;
        this.combat = combat;
        this.armor = armor;
        this.retaliation = retaliation;
    }

    /** Inyecta el vinculo de alma gemela: si el dueño golpea a SU NPC, el golpe se ignora del todo (no la enfada ni la blinda). */
    public void setBond(com.nemeles.npcai.BondManager bond) { this.bond = bond; }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = false)
    public void onDamage(EntityDamageByEntityEvent e) {
        AiConfig c = cfg.get();
        if (c == null) return;

        Player attacker = resolvePlayer(e.getDamager());
        if (attacker == null) return;

        Entity victim = e.getEntity();
        String raw = victim.getName();
        NpcPersona persona = c.match(raw == null ? null : ChatColor.stripColor(raw));
        if (persona == null) return;
        if (victim.getUniqueId().equals(attacker.getUniqueId())) return;

        // ALMA GEMELA: el dueño del vinculo NO puede dañar a SU NPC. Ignoramos el golpe por completo (lo cancela
        // LunaBondPerks en LOWEST): ni baja afinidad, ni la pone "en guardia", ni le viste armadura, ni cuenta golpes.
        if (bond != null && bond.isProtectedHit(attacker.getUniqueId(), persona.key)) return;

        double finalDmg = e.getFinalDamage();

        // ¿este golpe ENGANCHA al NPC en combate? Se PERDONA el primer golpe (puede ser sin querer): el NPC solo
        // pasa a atacar cuando su vida baja al umbral (p.ej. 40 de 50) o cuando ya es un enemigo declarado por afinidad.
        boolean engaged = false;
        if (victim instanceof org.bukkit.entity.LivingEntity lv) {
            double post = lv.getHealth() - finalDmg;
            engaged = post <= c.npcHealth * c.npcAttackHealthPercent;
        }
        boolean haveAff = affinity != null && c.hostilityEnabled;
        int aff = 0;
        if (haveAff) { try { aff = affinity.get(persona.key, attacker.getUniqueId()); } catch (Throwable ignored) { } }
        if (haveAff && aff <= c.hostHostileThreshold) engaged = true;   // ya te odia: se defiende desde el 1er golpe

        // SIEMPRE: el golpe se SIENTE (animacion + quejido), se viste de armadura, y se marca "recibio dano" (regen).
        try { victim.playEffect(org.bukkit.EntityEffect.HURT); } catch (Throwable ignored) { }
        try { victim.getWorld().playSound(victim.getLocation(), "entity.player.hurt", 1f, 1.05f); } catch (Throwable ignored) { }
        if (armor != null) { try { armor.onAttacked(victim, persona); } catch (Throwable ignored) { } }
        if (combat != null) { try { combat.markHit(victim); } catch (Throwable ignored) { } }

        long now = System.currentTimeMillis();
        Long t = lastTold.get(attacker.getUniqueId());
        boolean tell = (t == null || now - t > 4000);
        if (engaged) {
            // ahora SI: rencor (baja afinidad), aviso de enfurecer y retaliacion.
            if (haveAff) { try { affinity.bump(persona.key, attacker.getUniqueId(), -c.hostAttackDrop); } catch (Throwable ignored) { } }
            if (tell && haveAff) {
                lastTold.put(attacker.getUniqueId(), now);
                attacker.sendActionBar(net.kyori.adventure.text.Component.text(
                        ChatColor.translateAlternateColorCodes('&', "&c⚠ Has enfurecido a " + persona.name + ".")));
            }
            if (retaliation != null) { try { retaliation.onHarassed(attacker, victim, persona, true); } catch (Throwable ignored) { } }
        } else if (tell) {
            // 1er golpe / aun no enganchado: solo se pone EN GUARDIA (armadura), sin atacar ni guardar rencor.
            lastTold.put(attacker.getUniqueId(), now);
            attacker.sendActionBar(net.kyori.adventure.text.Component.text(
                    ChatColor.translateAlternateColorCodes('&', "&e" + persona.name + " se pone en guardia... no sigas.")));
        }

        // P1: ¿este golpe deja al NPC sin vida? Gestionamos NOSOTROS la muerte (sin loot) + respawn.
        if (combat != null && victim instanceof org.bukkit.entity.LivingEntity le) {
            try {
                if (le.getHealth() - finalDmg <= 0.0) {
                    net.citizensnpcs.api.npc.NPC npc = net.citizensnpcs.api.CitizensAPI.getNPCRegistry().getNPC(victim);
                    if (npc != null && combat.handleLethal(npc, attacker)) {
                        e.setDamage(0.0);
                        e.setCancelled(true);
                    }
                }
            } catch (Throwable ignored) { }
        }
    }

    /**
     * Muerte AMBIENTAL (caida, fuego, lava, ahogo, asfixia, cactus, VACIO, /kill, borde de mundo): no hay
     * entidad atacante, asi que onDamage no la cubre. Aqui la encauzamos por la misma muerte-limpia + respawn
     * en casa, para que un protagonista que se cae a la lava o al vacio reaparezca en su sitio y no desaparezca.
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onEnvDamage(EntityDamageEvent e) {
        if (e instanceof EntityDamageByEntityEvent) return;   // el dano de entidad ya lo gestiona onDamage
        if (combat == null) return;
        AiConfig c = cfg.get();
        if (c == null) return;
        Entity victim = e.getEntity();
        if (!(victim instanceof org.bukkit.entity.LivingEntity le)) return;
        String raw = victim.getName();
        NpcPersona persona = c.match(raw == null ? null : ChatColor.stripColor(raw));
        if (persona == null) return;

        // ALMA GEMELA: si Luna tiene dueño, NO se quema (el dueño no puede prenderla ni empujarla a lava, y tampoco
        // muere por fuego/lava ambiental). Apagamos su fuego y anulamos el daño. El resto de causas siguen normales.
        if (bond != null && bond.enabled() && bond.hasOwner() && persona.key.equals(bond.persona())) {
            EntityDamageEvent.DamageCause cause = e.getCause();
            if (cause == EntityDamageEvent.DamageCause.FIRE
                    || cause == EntityDamageEvent.DamageCause.FIRE_TICK
                    || cause == EntityDamageEvent.DamageCause.LAVA) {
                try { le.setFireTicks(0); } catch (Throwable ignored) { }
                e.setDamage(0.0);
                e.setCancelled(true);
                return;
            }
        }
        try {
            double finalDmg = e.getFinalDamage();
            if (le.getHealth() - finalDmg <= 0.0) {
                net.citizensnpcs.api.npc.NPC npc = net.citizensnpcs.api.CitizensAPI.getNPCRegistry().getNPC(victim);
                if (npc != null && combat.handleLethal(npc, null)) {
                    e.setDamage(0.0);
                    e.setCancelled(true);
                }
            }
        } catch (Throwable ignored) { }
    }

    /**
     * El jugador MURIO: cortamos el "combate por incidente" de TODOS los NPCs hacia el. Asi, cuando reaparezca,
     * nadie le ataca en bucle por una pelea anterior; solo volveran a pelear si el les vuelve a pegar.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onPlayerDeath(PlayerDeathEvent e) {
        if (retaliation == null) return;
        try { retaliation.calmPlayer(e.getEntity().getUniqueId()); } catch (Throwable ignored) { }
    }

    private Player resolvePlayer(Entity damager) {
        if (damager instanceof Player p) return p;
        if (damager instanceof Projectile pr) {
            ProjectileSource src = pr.getShooter();
            if (src instanceof Player p) return p;
        }
        return null;
    }
}
