package com.nemeles.jobs.weed;

import org.bukkit.GameMode;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

/** Plantar (clic derecho con semilla) y cosechar (romper la planta). */
public final class WeedListener implements Listener {

    private final WeedManager weed;

    public WeedListener(WeedManager weed) {
        this.weed = weed;
    }

    @EventHandler(ignoreCancelled = false)
    public void onPlant(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (e.getHand() != EquipmentSlot.HAND) return;
        if (e.getClickedBlock() == null) return;
        ItemStack item = e.getItem();
        if (!weed.items().is(item, "seed")) return;

        e.setCancelled(true); // evita plantar la semilla como cultivo vanilla
        Player player = e.getPlayer();
        boolean planted = weed.plant(player, e.getClickedBlock());
        if (planted && player.getGameMode() != GameMode.CREATIVE) {
            ItemStack hand = player.getInventory().getItemInMainHand();
            if (weed.items().is(hand, "seed")) {
                hand.setAmount(hand.getAmount() - 1);
            }
        }
    }

    /**
     * Clic derecho SOBRE una planta nuestra: SIEMPRE cancelado (mata el exploit de "ordeñar" bayas
     * vanilla del arbusto = comida infinita). Con POLVO DE HUESO en la mano, arranca el minijuego
     * de fertilizar.
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onPlantInteract(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getClickedBlock() == null) return;
        Block b = e.getClickedBlock();
        if (!weed.isPlant(b.getLocation())) return;
        e.setCancelled(true);   // nada de bayas gratis ni interacciones vanilla con la mata
        if (e.getHand() != EquipmentSlot.HAND) return;
        ItemStack item = e.getItem();
        if (item != null && item.getType() == org.bukkit.Material.BONE_MEAL) {
            weed.care(e.getPlayer(), b);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHarvest(BlockBreakEvent e) {
        Block b = e.getBlock();
        if (!weed.isPlant(b.getLocation())) {
            // ¿Rompieron la mitad de ARRIBA de la mata doble? Cosechar la de abajo (que es la real).
            Block below = b.getRelative(0, -1, 0);
            if (b.getType() == org.bukkit.Material.LARGE_FERN && weed.isPlant(below.getLocation())) {
                e.setDropItems(false);
                weed.harvest(e.getPlayer(), below);
                below.setType(org.bukkit.Material.AIR, false);
            }
            return;
        }
        e.setDropItems(false); // que no caigan bayas/helechos vanilla; soltamos nuestros cogollos
        weed.harvest(e.getPlayer(), b);
    }
}
