package com.nemeles.territories.papi;

import com.nemeles.core.api.NemelesApi;
import com.nemeles.core.api.faction.Faction;
import com.nemeles.core.api.territory.TerritorySnapshot;
import com.nemeles.territories.TerritoryServiceImpl;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.Locale;
import java.util.Optional;

/** %territory_here% %territory_owner% %territory_state% %territory_owned% %territory_contested% */
public final class TerritoryPlaceholders extends PlaceholderExpansion {

    private final Plugin plugin;
    private final TerritoryServiceImpl svc;

    public TerritoryPlaceholders(Plugin plugin, TerritoryServiceImpl svc) {
        this.plugin = plugin;
        this.svc = svc;
    }

    @Override public String getIdentifier() { return "territory"; }
    @Override public String getAuthor() { return "Nemeles"; }
    @Override public String getVersion() { return plugin.getDescription().getVersion(); }
    @Override public boolean persist() { return true; }
    @Override public boolean canRegister() { return true; }

    @Override
    public String onRequest(OfflinePlayer player, String params) {
        if (player == null) return "";
        String id = params.toLowerCase(Locale.ROOT);
        if (id.equals("owned")) {
            int fac = factionOf(player);
            return String.valueOf(fac < 0 ? 0 : svc.countOwnedBy(fac));
        }
        Player online = player.getPlayer();
        if (online == null) return "";
        Optional<TerritorySnapshot> at = svc.territoryAt(online.getLocation());
        switch (id) {
            case "here" -> { return at.map(TerritorySnapshot::name).orElse(""); }
            case "owner" -> { return at.map(s -> s.isNeutral() ? "neutral" : tag(s.ownerFactionId())).orElse(""); }
            case "state" -> { return at.map(s -> svc.isContested(s.id()) ? "EN DISPUTA" : s.state()).orElse(""); }
            case "contested" -> { return at.map(s -> svc.isContested(s.id()) ? "si" : "no").orElse("no"); }
            default -> { return null; }
        }
    }

    private int factionOf(OfflinePlayer p) {
        try { return NemelesApi.factions().getFactionOf(p.getUniqueId()).map(Faction::id).orElse(-1); }
        catch (Throwable t) { return -1; }
    }

    private String tag(int id) {
        try { return NemelesApi.factions().getFaction(id).map(Faction::tag).orElse("?"); }
        catch (Throwable t) { return "?"; }
    }
}
