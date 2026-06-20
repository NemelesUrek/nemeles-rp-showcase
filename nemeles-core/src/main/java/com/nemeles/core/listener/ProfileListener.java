package com.nemeles.core.listener;

import com.nemeles.core.api.economy.MoneyType;
import com.nemeles.core.economy.DualEconomyService;
import com.nemeles.core.profile.ProfileServiceImpl;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;

/** Crea/carga el perfil y precarga la cuenta economica al entrar; guarda y libera al salir. */
public final class ProfileListener implements Listener {

    private final Plugin plugin;
    private final ProfileServiceImpl profiles;
    private final DualEconomyService economy;

    public ProfileListener(Plugin plugin, ProfileServiceImpl profiles, DualEconomyService economy) {
        this.plugin = plugin;
        this.profiles = profiles;
        this.economy = economy;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) {
        final String name = e.getPlayer().getName();
        profiles.handleJoin(e.getPlayer().getUniqueId(), name).exceptionally(ex -> {
            plugin.getLogger().warning("[PROFILE] Error cargando perfil de " + name + ": " + ex.getMessage());
            return null;
        });
        // Precarga (y crea si no existe) la cuenta economica.
        economy.balance(e.getPlayer().getUniqueId(), MoneyType.EFECTIVO).exceptionally(ex -> null);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) {
        profiles.handleQuit(e.getPlayer().getUniqueId()).exceptionally(ex -> {
            plugin.getLogger().warning("[PROFILE] Error guardando perfil al salir: " + ex.getMessage());
            return null;
        });
    }
}
