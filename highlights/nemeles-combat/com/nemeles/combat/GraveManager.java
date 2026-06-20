package com.nemeles.combat;

import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Chest;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * TUMBA (loot box temporal): al morir del todo, tus objetos van a un COFRE en el sitio de la muerte
 * en vez de soltarse al suelo y despawnear. El cofre se rompe SOLO cuando lo vacías (o lo deja un
 * jugador vacío). Es un cofre vanilla marcado con PDC, así que se ve y se abre igual en Bedrock vía
 * Geyser, y sobrevive a reinicios (si se pierde el marcado, queda como cofre normal: inofensivo).
 */
public final class GraveManager implements Listener {

    private final NamespacedKey graveKey;

    public GraveManager(Plugin plugin) { this.graveKey = new NamespacedKey(plugin, "grave"); }

    /** Crea la tumba con los items (reparte en cofres de 27 apilados hacia arriba si hace falta). */
    public void createGrave(Location deathLoc, String owner, List<ItemStack> items) {
        if (deathLoc == null || deathLoc.getWorld() == null || items == null || items.isEmpty()) return;
        World w = deathLoc.getWorld();
        List<ItemStack> remaining = new ArrayList<>();
        for (ItemStack it : items) if (it != null && it.getType() != Material.AIR) remaining.add(it);
        if (remaining.isEmpty()) return;

        Block cur = safeBlock(deathLoc.getBlock());
        int guard = 0;
        while (!remaining.isEmpty() && guard++ < 6) {
            cur.setType(Material.CHEST, false);   // sin physics: no se cae si no hay soporte
            if (!(cur.getState() instanceof Chest snap)) break;
            snap.setCustomName(ChatColor.GOLD + "Tumba de " + owner);
            snap.getPersistentDataContainer().set(graveKey, PersistentDataType.STRING, owner);
            snap.update(true, false);
            Inventory inv = ((Chest) cur.getState()).getBlockInventory();
            Iterator<ItemStack> it = remaining.iterator();
            int slot = 0;
            while (it.hasNext() && slot < inv.getSize()) { inv.setItem(slot++, it.next()); it.remove(); }
            Block up = cur.getRelative(BlockFace.UP);
            if (!remaining.isEmpty() && up.getType().isAir()) cur = up;
            else break;
        }
        // si no cupo todo (sin sitio): suelta el resto en el sitio (caso raro)
        for (ItemStack rest : remaining) if (rest != null) w.dropItemNaturally(deathLoc.clone().add(0.5, 0.5, 0.5), rest);
        w.playSound(deathLoc, Sound.ENTITY_VILLAGER_DEATH, 0.7f, 0.8f);
    }

    /** Bloque donde poner el cofre: si estás en el aire baja hasta el suelo; si en sólido sube a aire. */
    private Block safeBlock(Block b) {
        int guard = 0;
        if (b.getType().isAir()) {
            while (b.getRelative(BlockFace.DOWN).getType().isAir() && guard++ < 12) b = b.getRelative(BlockFace.DOWN);
            return b;
        }
        while (!b.getType().isAir() && guard++ < 5) b = b.getRelative(BlockFace.UP);
        return b;
    }

    /** Al cerrar una tumba vacía: la tumba desaparece (poof). */
    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        InventoryHolder h = e.getInventory().getHolder();
        if (!(h instanceof Chest chest)) return;
        if (!chest.getPersistentDataContainer().has(graveKey, PersistentDataType.STRING)) return;
        for (ItemStack it : e.getInventory().getContents()) if (it != null && it.getType() != Material.AIR) return;  // aún queda algo
        Block b = chest.getBlock();
        b.getWorld().spawnParticle(Particle.POOF, b.getLocation().add(0.5, 0.5, 0.5), 12, 0.3, 0.3, 0.3, 0.01);
        b.getWorld().playSound(b.getLocation(), Sound.BLOCK_WOOD_BREAK, 1f, 1f);
        b.setType(Material.AIR);
    }
}
