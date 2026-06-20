package com.nemeles.combat.papi;

import com.nemeles.combat.DownedManager;
import com.nemeles.combat.GunDef;
import com.nemeles.combat.GunRegistry;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.util.Locale;

/** %combat_state% %combat_isdowned% %combat_bleed% %combat_ammo% */
public final class CombatPlaceholders extends PlaceholderExpansion {

    private final Plugin plugin;
    private final DownedManager downed;
    private final GunRegistry guns;

    public CombatPlaceholders(Plugin plugin, DownedManager downed, GunRegistry guns) {
        this.plugin = plugin;
        this.downed = downed;
        this.guns = guns;
    }

    @Override public String getIdentifier() { return "combat"; }
    @Override public String getAuthor() { return "Nemeles"; }
    @Override public String getVersion() { return plugin.getDescription().getVersion(); }
    @Override public boolean persist() { return true; }
    @Override public boolean canRegister() { return true; }

    @Override
    public String onRequest(OfflinePlayer player, String params) {
        if (player == null) return "";
        switch (params.toLowerCase(Locale.ROOT)) {
            case "state" -> { return downed.stateOf(player.getUniqueId()).name(); }
            case "isdowned" -> { return downed.isDowned(player.getUniqueId()) ? "true" : "false"; }
            case "bleed" -> {
                int b = downed.bleedoutSecondsLeft(player.getUniqueId());
                return b < 0 ? "" : String.valueOf(b);
            }
            case "ammo" -> {
                Player online = player.getPlayer();
                if (online == null) return "";
                ItemStack it = online.getInventory().getItemInMainHand();
                GunDef g = guns.fromItem(it);
                if (g == null || g.ammoId == null) return "";
                return guns.mag(it) + "/" + g.magSize;
            }
            default -> { return null; }
        }
    }
}
