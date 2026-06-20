package com.nemeles.territories.listener;

import com.nemeles.territories.TerritoryManager;
import org.bukkit.Location;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/** Solo feedback de entrada/salida y marca anti-AFK. Filtra por cambio de bloque (no por cada paquete). */
public final class TerritoryMoveListener implements Listener {

    private final TerritoryManager manager;

    public TerritoryMoveListener(TerritoryManager manager) {
        this.manager = manager;
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onMove(PlayerMoveEvent e) {
        Location from = e.getFrom();
        Location to = e.getTo();
        if (to == null) return;
        if (from.getBlockX() == to.getBlockX() && from.getBlockY() == to.getBlockY() && from.getBlockZ() == to.getBlockZ()) {
            return; // no cambio de bloque: la inmensa mayoria de eventos
        }
        manager.onMove(e.getPlayer(), to);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        manager.onQuit(e.getPlayer());
    }
}
