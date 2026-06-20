package com.nemeles.combat;

import org.bukkit.NamespacedKey;
import org.bukkit.plugin.Plugin;

/** Claves PersistentDataContainer del modulo (identidad de items sin CustomModelData -> crossplay). */
public final class CombatKeys {

    public final NamespacedKey gun;        // STRING  gun_id
    public final NamespacedKey mag;        // INTEGER balas en cargador
    public final NamespacedKey ammo;       // STRING  ammo_id
    public final NamespacedKey contraband; // INTEGER 1 = contrabando (se pierde/dropea al morir)
    public final NamespacedKey skin;       // STRING  id de skin cosmetica (no cambia stats)

    public CombatKeys(Plugin p) {
        this.gun = new NamespacedKey(p, "gun_id");
        this.mag = new NamespacedKey(p, "mag");
        this.ammo = new NamespacedKey(p, "ammo_id");
        this.contraband = new NamespacedKey(p, "contraband");
        this.skin = new NamespacedKey(p, "skin");
    }
}
