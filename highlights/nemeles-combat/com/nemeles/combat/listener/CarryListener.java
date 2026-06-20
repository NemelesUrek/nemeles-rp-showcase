package com.nemeles.combat.listener;

import com.nemeles.combat.DownedManager;
import com.nemeles.combat.MedItems;
import org.bukkit.ChatColor;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EquipmentSlot;

/**
 * CARGAR A HOMBROS (estilo FiveM/wasabi): agachado + clic derecho sobre un DERRIBADO con la mano
 * VACIA = te lo echas al hombro (addPassenger, crossplay-safe). Repetir = soltarlo. Con un item
 * medico en mano NO carga (eso es transfusion/tratamiento). El porteador va lento (lo aplica el
 * tick del DownedManager) — llevalo al hospital o a un medico.
 */
public final class CarryListener implements Listener {

    private final DownedManager downed;

    public CarryListener(DownedManager downed) { this.downed = downed; }

    @EventHandler
    public void onInteract(PlayerInteractEntityEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        if (!(e.getRightClicked() instanceof Player target)) return;
        Player p = e.getPlayer();
        if (!downed.isDowned(target.getUniqueId())) return;
        e.setCancelled(true);
        if (!p.isSneaking()) return;
        if (downed.isDowned(p.getUniqueId())) return;
        if (MedItems.idOf(p.getInventory().getItemInMainHand()) != null) return;   // mano con material = tratar

        if (p.getPassengers().contains(target)) {
            p.removePassenger(target);
            p.sendMessage(ChatColor.YELLOW + "Dejas a " + target.getName() + " en el suelo, con cuidado.");
            target.sendMessage(ChatColor.YELLOW + p.getName() + " te deja en el suelo.");
            return;
        }
        if (!p.getPassengers().isEmpty()) {
            p.sendMessage(ChatColor.RED + "Ya llevas a alguien encima. No eres una ambulancia.");
            return;
        }
        if (p.addPassenger(target)) {
            p.sendMessage(ChatColor.GREEN + "Te echas a " + target.getName()
                    + " al hombro. " + ChatColor.GRAY + "Camina (lento) hasta un médico o el hospital; agáchate y clic para soltarlo.");
            target.sendMessage(ChatColor.GREEN + p.getName() + " te carga a hombros. Aguanta.");
            try { p.playSound(p.getLocation(), Sound.ENTITY_ARMOR_STAND_PLACE, 0.7f, 0.7f); } catch (Throwable ignored) { }
        }
    }
}
