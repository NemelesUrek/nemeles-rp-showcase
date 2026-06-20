package com.nemeles.combat;

import com.nemeles.core.api.combat.CombatService;
import com.nemeles.core.api.combat.DownState;
import org.bukkit.inventory.ItemStack;

import java.util.UUID;

public final class CombatServiceImpl implements CombatService {

    private final DownedManager downed;
    private final GunRegistry guns;
    private final AmmoManager ammo;

    public CombatServiceImpl(DownedManager downed, GunRegistry guns, AmmoManager ammo) {
        this.downed = downed;
        this.guns = guns;
        this.ammo = ammo;
    }

    @Override public DownState stateOf(UUID player) { return downed.stateOf(player); }
    @Override public boolean isDowned(UUID player) { return downed.isDowned(player); }
    @Override public int bleedoutSecondsLeft(UUID player) { return downed.bleedoutSecondsLeft(player); }
    @Override public boolean isGun(ItemStack item) { return guns.isGun(item); }
    @Override public String gunIdOf(ItemStack item) { return guns.gunIdOf(item); }
    @Override public ItemStack createGun(String gunId) { return guns.create(gunId); }
    @Override public ItemStack createAmmo(String ammoId, int amount) { return ammo.ammoStack(ammoId, amount); }
    @Override public ItemStack createMedItem(String medId, int amount) { return MedItems.create(medId, amount); }
    @Override public boolean forceDown(UUID player, UUID source, String cause) { return downed.forceDown(player, source, cause); }
    @Override public boolean revive(UUID target, UUID byReviver) { return downed.revive(target, byReviver); }
    @Override public void finishOff(UUID player, String cause) { downed.finishOff(player, cause); }
}
