package com.nemeles.combat;

import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Municion = item vanilla identificado por PDC ammo_id; se consume (sink). Gestiona la recarga. */
public final class AmmoManager {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();

    private final Plugin plugin;
    private final CombatKeys keys;
    private final GunRegistry guns;
    private final Set<UUID> reloading = ConcurrentHashMap.newKeySet();

    private com.nemeles.combat.render.GunModelService gunModel;   // animacion de recarga (opcional, soft-dep)
    public void setGunModel(com.nemeles.combat.render.GunModelService gunModel) { this.gunModel = gunModel; }

    public AmmoManager(Plugin plugin, CombatKeys keys, GunRegistry guns) {
        this.plugin = plugin;
        this.keys = keys;
        this.guns = guns;
    }

    public ItemStack ammoStack(String ammoId, int n) {
        Material mat = guns.ammoItem(ammoId);
        if (mat == null) return null;
        ItemStack it = new ItemStack(mat, Math.max(1, n));
        ItemMeta m = it.getItemMeta();
        if (m != null) {
            m.getPersistentDataContainer().set(keys.ammo, PersistentDataType.STRING, ammoId);
            int cmd = guns.ammoModelData(ammoId);
            if (cmd > 0) m.setCustomModelData(cmd);   // textura del pack (opt-in); 0 = vanilla
            m.displayName(LEGACY.deserialize("§fMunición §7(" + ammoId + ")"));
            it.setItemMeta(m);
        }
        return it;
    }

    private boolean isAmmo(ItemStack it, String ammoId) {
        if (it == null || !it.hasItemMeta()) return false;
        String a = it.getItemMeta().getPersistentDataContainer().get(keys.ammo, PersistentDataType.STRING);
        return ammoId.equals(a);
    }

    public boolean hasAmmoItem(Player p, String ammoId, int n) {
        int c = 0;
        for (ItemStack it : p.getInventory().getContents()) {
            if (isAmmo(it, ammoId)) { c += it.getAmount(); if (c >= n) return true; }
        }
        return c >= n;
    }

    public int takeAmmoItem(Player p, String ammoId, int max) {
        int left = max;
        ItemStack[] cont = p.getInventory().getContents();
        for (int i = 0; i < cont.length && left > 0; i++) {
            ItemStack it = cont[i];
            if (!isAmmo(it, ammoId)) continue;
            int take = Math.min(left, it.getAmount());
            it.setAmount(it.getAmount() - take);
            left -= take;
            if (it.getAmount() <= 0) p.getInventory().setItem(i, null);
        }
        return max - left;
    }

    public void giveAmmo(Player p, String ammoId, int n) {
        ItemStack st = ammoStack(ammoId, n);
        if (st != null) p.getInventory().addItem(st);
    }

    public void reload(Player p, ItemStack gunItem, GunDef def) {
        if (def.ammoId == null) return;
        if (reloading.contains(p.getUniqueId())) return;
        if (guns.mag(gunItem) >= def.magSize) return;
        if (!hasAmmoItem(p, def.ammoId, 1)) { p.sendActionBar(LEGACY.deserialize("§cSin munición de " + def.ammoId)); return; }
        reloading.add(p.getUniqueId());
        if (gunModel != null) gunModel.onReload(p, def);   // animacion de recarga 3a persona (propaga a Bedrock)
        p.playSound(p.getLocation(), Sound.BLOCK_PISTON_CONTRACT, 1f, 1.2f);
        p.sendActionBar(LEGACY.deserialize("§eRecargando..."));
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            reloading.remove(p.getUniqueId());
            ItemStack inHand = p.getInventory().getItemInMainHand();
            GunDef d2 = guns.fromItem(inHand);
            if (d2 == null || !d2.id.equals(def.id)) return;     // cambio de arma durante la recarga
            int need = def.magSize - guns.mag(inHand);
            if (need <= 0) return;
            int taken = takeAmmoItem(p, def.ammoId, need);
            if (taken <= 0) { p.sendActionBar(LEGACY.deserialize("§cSin munición")); return; }
            guns.setMag(inHand, guns.mag(inHand) + taken);
            p.getInventory().setItemInMainHand(inHand);
            p.playSound(p.getLocation(), Sound.ITEM_CROSSBOW_LOADING_END, 1f, 1f);
            p.sendActionBar(LEGACY.deserialize("§aRecargado: " + guns.mag(inHand) + "/" + def.magSize));
            if (gunModel != null) gunModel.onReloadEnd(p);   // asegura volver a idle al terminar la recarga
        }, Math.max(1, def.reloadTicks));
    }

    public boolean isReloading(UUID id) { return reloading.contains(id); }
}
