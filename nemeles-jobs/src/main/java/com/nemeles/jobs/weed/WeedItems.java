package com.nemeles.jobs.weed;

import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.List;

/**
 * Items de la marihuana, basados en materiales VANILLA + un tag PDC (crossplay seguro, sin pack).
 *  - "seed" semilla  (WHEAT_SEEDS)  - "bud" cogollo crudo (FERN)  - "bag" bolsa procesada (DRIED_KELP)
 */
public final class WeedItems {

    private final NamespacedKey key;
    // CustomModelData para las texturas del pack (0 = sin textura, item vanilla). Opt-in por config.
    private final int seedCmd, budCmd, bagCmd;

    public WeedItems(Plugin plugin) {
        this.key = new NamespacedKey(plugin, "weed_type");
        this.seedCmd = Math.max(0, plugin.getConfig().getInt("weed.model-data.seed", 0));
        this.budCmd  = Math.max(0, plugin.getConfig().getInt("weed.model-data.bud", 0));
        this.bagCmd  = Math.max(0, plugin.getConfig().getInt("weed.model-data.bag", 0));
    }

    public ItemStack seed(int amount) {
        return tagged(Material.WHEAT_SEEDS, amount, "seed", seedCmd,
                ChatColor.GREEN + "Semilla de Marihuana",
                "Plántala (clic derecho en tierra) en una zona de cultivo.");
    }

    public ItemStack bud(int amount) {
        return tagged(Material.FERN, amount, "bud", budCmd,
                ChatColor.DARK_GREEN + "Cogollo de Marihuana",
                "Procesa 3 cogollos en 1 bolsa con /weed procesar.");
    }

    public ItemStack bag(int amount) {
        return tagged(Material.DRIED_KELP, amount, "bag", bagCmd,
                ChatColor.DARK_GREEN + "" + ChatColor.BOLD + "Bolsa de Marihuana",
                "Véndela en el mercado negro con /weed vender.");
    }

    private ItemStack tagged(Material material, int amount, String type, int cmd, String name, String lore) {
        ItemStack it = new ItemStack(material, Math.max(1, amount));
        ItemMeta meta = it.getItemMeta();
        meta.setDisplayName(name);
        meta.setLore(List.of(ChatColor.GRAY + lore, ChatColor.DARK_GRAY + "Artículo ilegal"));
        meta.getPersistentDataContainer().set(key, PersistentDataType.STRING, type);
        if (cmd > 0) meta.setCustomModelData(cmd);   // textura del pack (opt-in); 0 = vanilla
        it.setItemMeta(meta);
        return it;
    }

    public String typeOf(ItemStack it) {
        if (it == null || !it.hasItemMeta()) return null;
        return it.getItemMeta().getPersistentDataContainer().get(key, PersistentDataType.STRING);
    }

    public boolean is(ItemStack it, String type) {
        return type.equals(typeOf(it));
    }
}
