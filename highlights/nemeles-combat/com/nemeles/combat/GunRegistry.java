package com.nemeles.combat;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;

/** Crea/identifica armas custom via PersistentDataContainer (sin CustomModelData -> paridad Bedrock). */
public final class GunRegistry {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();

    private final Plugin plugin;
    private final CombatKeys keys;
    private final CombatConfig cfg;

    public GunRegistry(Plugin plugin, CombatKeys keys, CombatConfig cfg) {
        this.plugin = plugin;
        this.keys = keys;
        this.cfg = cfg;
    }

    public GunDef def(String id) { return cfg.guns.get(id); }
    public double hitBoxGrow() { return cfg.hitBoxGrow; }   // precision del hitscan de balas
    public Material ammoItem(String ammoId) { return cfg.ammoItems.get(ammoId); }
    public int ammoModelData(String ammoId) { return cfg.ammoModelData.getOrDefault(ammoId, 0); }

    public GunDef fromItem(ItemStack it) {
        if (it == null || it.getType().isAir() || !it.hasItemMeta()) return null;
        String id = it.getItemMeta().getPersistentDataContainer().get(keys.gun, PersistentDataType.STRING);
        return id == null ? null : cfg.guns.get(id);
    }

    public boolean isGun(ItemStack it) { return fromItem(it) != null; }

    public String gunIdOf(ItemStack it) {
        if (it == null || !it.hasItemMeta()) return null;
        return it.getItemMeta().getPersistentDataContainer().get(keys.gun, PersistentDataType.STRING);
    }

    /** Id de la skin cosmetica aplicada al arma (PDC keys.skin), o null si no tiene. Lo usa GunModelService. */
    public String skinIdOf(ItemStack it) {
        if (it == null || !it.hasItemMeta()) return null;
        return it.getItemMeta().getPersistentDataContainer().get(keys.skin, PersistentDataType.STRING);
    }

    public int mag(ItemStack it) {
        if (it == null || !it.hasItemMeta()) return 0;
        Integer m = it.getItemMeta().getPersistentDataContainer().get(keys.mag, PersistentDataType.INTEGER);
        return m == null ? 0 : m;
    }

    public void setMag(ItemStack it, int n) {
        ItemMeta m = it.getItemMeta();
        if (m == null) return;
        int v = Math.max(0, n);
        m.getPersistentDataContainer().set(keys.mag, PersistentDataType.INTEGER, v);
        GunDef d = fromItem(it);
        if (d != null) applyVisual(m, d, v);
        it.setItemMeta(m);
    }

    public ItemStack create(String id) {
        GunDef d = cfg.guns.get(id);
        if (d == null) return null;
        ItemStack it = new ItemStack(d.baseItem);
        ItemMeta m = it.getItemMeta();
        if (m == null) return it;
        m.getPersistentDataContainer().set(keys.gun, PersistentDataType.STRING, id);
        m.getPersistentDataContainer().set(keys.mag, PersistentDataType.INTEGER, d.magSize);
        if (!d.legal) m.getPersistentDataContainer().set(keys.contraband, PersistentDataType.INTEGER, 1);
        applyVisual(m, d, d.magSize);
        it.setItemMeta(m);
        return it;
    }

    /** Crea un arma con una SKIN cosmetica aplicada (mismas stats; solo cambia el visual). */
    public ItemStack createSkinned(String weaponId, String skinId) {
        ItemStack it = create(weaponId);
        if (it == null) return null;
        GunSkin gs = cfg.skins.get(skinId);
        if (gs == null || !gs.weapon.equalsIgnoreCase(weaponId)) return it;   // skin invalida -> arma base
        ItemMeta m = it.getItemMeta();
        if (m == null) return it;
        m.getPersistentDataContainer().set(keys.skin, PersistentDataType.STRING, skinId);
        applyVisual(m, cfg.guns.get(weaponId), mag(it));
        it.setItemMeta(m);
        return it;
    }

    public GunSkin skinDef(String id) { return cfg.skins.get(id); }

    /** Todas las skins definidas (para el cofre GUI de skins). */
    public java.util.Collection<GunSkin> allSkins() { return cfg.skins.values(); }

    private void applyVisual(ItemMeta m, GunDef d, int mag) {
        GunSkin skin = null;
        String skinId = m.getPersistentDataContainer().get(keys.skin, PersistentDataType.STRING);
        if (skinId != null) { GunSkin gs = cfg.skins.get(skinId); if (gs != null && gs.weapon.equalsIgnoreCase(d.id)) skin = gs; }
        int cmd = (skin != null && skin.modelData > 0) ? skin.modelData : d.modelData;
        if (cmd > 0) m.setCustomModelData(cmd);   // skin > arma base > 0(vanilla); textura del pack (opt-in)
        m.displayName(LEGACY.deserialize(skin != null ? org.bukkit.ChatColor.translateAlternateColorCodes('&', skin.name) : ("§e" + niceName(d.id))));
        List<Component> lore = new ArrayList<>();
        if (d.ammoId != null) lore.add(LEGACY.deserialize("§7Munición: §f" + mag + "/" + d.magSize + " §8(" + d.ammoId + ")"));
        lore.add(LEGACY.deserialize(d.legal ? "§8arma legal" : "§4arma ilegal"));
        m.lore(lore);
    }

    private static String niceName(String id) {
        return switch (id.toLowerCase()) {
            case "pistol" -> "Pistola";
            case "smg" -> "Subfusil";
            case "rifle" -> "Rifle";
            case "shotgun" -> "Escopeta";
            case "knife" -> "Cuchillo";
            default -> id.isEmpty() ? id : Character.toUpperCase(id.charAt(0)) + id.substring(1);
        };
    }
}
