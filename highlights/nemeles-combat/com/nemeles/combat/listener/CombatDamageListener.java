package com.nemeles.combat.listener;

import com.nemeles.combat.CombatTagManager;
import com.nemeles.combat.DownedManager;
import com.nemeles.core.api.NemelesApi;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.projectiles.ProjectileSource;

import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

/** Intercepta el golpe letal -> DOWNED (o muerte permanente en zona negra). Tambien marca combat-tag. */
public final class CombatDamageListener implements Listener {

    private static final Set<EntityDamageEvent.DamageCause> UNSAVABLE = EnumSet.of(
            EntityDamageEvent.DamageCause.VOID,
            EntityDamageEvent.DamageCause.KILL,
            EntityDamageEvent.DamageCause.WORLD_BORDER);

    private final DownedManager downed;
    private final CombatTagManager tags;

    public CombatDamageListener(DownedManager downed, CombatTagManager tags) {
        this.downed = downed;
        this.tags = tags;
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        UUID id = p.getUniqueId();

        Player attacker = (e instanceof EntityDamageByEntityEvent be) ? resolveAttacker(be.getDamager()) : null;
        if (attacker != null && !attacker.equals(p)) tags.tag(attacker.getUniqueId(), id);

        if (downed.isDowned(id)) { e.setCancelled(true); return; }      // invulnerable mientras derribado
        if (downed.isImmune(id)) { e.setCancelled(true); return; }      // inmunidad post-revive / respawn

        double finalHealth = p.getHealth() - e.getFinalDamage();
        // Caes al llegar a 1/2 corazon (no a 0): asi NUNCA tocas 0 de vida y el cliente (Bedrock)
        // no muestra la pantalla de muerte. El golpe que te dejaria a <=0.5 corazones te DERRIBA.
        if (finalHealth > 1.0) return;                                  // aun te quedan >1/2 corazon -> flujo normal

        UUID source = attacker != null ? attacker.getUniqueId() : null;
        try {
            if (NemelesApi.regions().isPermadeathZone(p.getLocation())) {
                // Cancelar el golpe y forzar la muerte nosotros: si dejaramos morir "vanilla", un Totem
                // de la Inmortalidad (u otro listener) podria salvar al jugador dejando el flag de
                // permadeath PEGADO -> arrasaria su inventario en una muerte futura sin relacion.
                // setHealth(0) mata directo (el totem no intercepta) y garantiza consumir el flag ya.
                e.setCancelled(true);
                downed.markPermadeath(id, source);
                try { p.setHealth(0.0); } catch (IllegalArgumentException ignored) { }
                return;
            }
        } catch (Throwable ignored) { }

        if (UNSAVABLE.contains(e.getCause())) return;                   // void/kill: muerte directa

        e.setCancelled(true);
        downed.knockDown(p, source, e.getCause().name());
    }

    private Player resolveAttacker(Entity damager) {
        if (damager instanceof Player p) return p;
        if (damager instanceof Projectile proj) {
            ProjectileSource src = proj.getShooter();
            if (src instanceof Player p) return p;
        }
        return null;
    }
}
