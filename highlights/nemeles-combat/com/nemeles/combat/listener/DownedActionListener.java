package com.nemeles.combat.listener;

import com.nemeles.combat.DownedManager;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;

/** Bloquea las ACCIONES físicas de un derribado (romper, colocar, pegar, soltar) — pero NO el chat
 *  ni los comandos: tienes que poder /rendirse, pedir ayuda o reaparecer estando en el suelo. */
public final class DownedActionListener implements Listener {

    private final DownedManager downed;

    public DownedActionListener(DownedManager downed) {
        this.downed = downed;
    }

    private boolean down(Player p) { return downed.isDowned(p.getUniqueId()); }

    @EventHandler(ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent e) { if (down(e.getPlayer())) e.setCancelled(true); }

    @EventHandler(ignoreCancelled = true)
    public void onInteractEntity(PlayerInteractEntityEvent e) { if (down(e.getPlayer())) e.setCancelled(true); }

    @EventHandler(ignoreCancelled = true)
    public void onAttack(EntityDamageByEntityEvent e) {
        if (e.getDamager() instanceof Player p && down(p)) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) { if (down(e.getPlayer())) e.setCancelled(true); }

    @EventHandler(ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) { if (down(e.getPlayer())) e.setCancelled(true); }

    @EventHandler(ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent e) { if (down(e.getPlayer())) e.setCancelled(true); }

    @EventHandler
    public void onInvOpen(InventoryOpenEvent e) {
        if (!(e.getPlayer() instanceof Player p) || !down(p)) return;
        // EXIME la ficha médica: un derribado SÍ puede abrir su radiografía para intentar atenderse.
        String title = e.getView().getTitle();
        if (title != null && (title.indexOf(com.nemeles.combat.body.BodyScreen.TAG_OVERVIEW) >= 0
                || title.indexOf(com.nemeles.combat.body.BodyScreen.TAG_DETALLE) >= 0)) return;
        e.setCancelled(true);
    }
    // NOTA: NO se bloquean comandos ni chat estando derribado (antes sí, y no podías ni /rendirse
    // ni reaparecer). Ahora tienes acceso total al chat para pedir ayuda, rendirte o lo que sea.
}
