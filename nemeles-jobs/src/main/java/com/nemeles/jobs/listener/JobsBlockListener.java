package com.nemeles.jobs.listener;

import com.nemeles.jobs.JobManager;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.data.Ageable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Paga al romper menas/cultivos. Anti-farm: ignora bloques colocados por jugadores y cultivos no maduros. */
public final class JobsBlockListener implements Listener {

    private final JobManager jobs;
    // Bloques colocados por jugadores (no pagan al romperse). Backstop de memoria por si crece mucho.
    private final Set<Location> placed = Collections.newSetFromMap(new ConcurrentHashMap<>());

    public JobsBlockListener(JobManager jobs) {
        this.jobs = jobs;
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        if (placed.size() > 200_000) placed.clear();
        placed.add(e.getBlock().getLocation());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        if (e.getPlayer().getGameMode() == GameMode.CREATIVE) return;
        Location loc = e.getBlock().getLocation();
        boolean wasPlaced = placed.remove(loc);
        Material mat = e.getBlock().getType();
        boolean mature = true;
        if (e.getBlock().getBlockData() instanceof Ageable age) {
            mature = age.getAge() >= age.getMaximumAge();
        }
        jobs.handleBreak(e.getPlayer(), mat, wasPlaced, mature);
        if (!wasPlaced) {
            jobs.applyHarvestPerks(e.getPlayer(), mat, mature, e.getBlock(), e.getPlayer().getInventory().getItemInMainHand());
        }
    }
}
