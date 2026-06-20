package com.nemeles.combat;

import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

/** Utilidad compartida para identificar/eliminar contrabando (items con PDC contraband=1). */
public final class Contraband {

    private Contraband() {}

    public static boolean is(ItemStack it, NamespacedKey key) {
        if (it == null || !it.hasItemMeta()) return false;
        Integer v = it.getItemMeta().getPersistentDataContainer().get(key, PersistentDataType.INTEGER);
        return v != null && v == 1;
    }

    /** Quita el contrabando del inventario del jugador; si {@code drop}, lo tira al suelo. Devuelve cuántos quitó. */
    public static int strip(Player p, NamespacedKey key, boolean drop) {
        if (p == null) return 0;
        var inv = p.getInventory();
        int n = 0;
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack it = inv.getItem(i);
            if (is(it, key)) {
                inv.setItem(i, null);
                if (drop && p.getWorld() != null) p.getWorld().dropItemNaturally(p.getLocation(), it);
                n++;
            }
        }
        return n;
    }
}
