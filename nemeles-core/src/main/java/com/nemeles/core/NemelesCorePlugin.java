package com.nemeles.core;

import com.nemeles.core.api.NemelesApi;
import com.nemeles.core.api.economy.MoneyType;
import com.nemeles.core.api.region.RegionService;
import com.nemeles.core.command.NemelesCommand;
import com.nemeles.core.db.CoreMigrations;
import com.nemeles.core.db.HikariDatabaseProvider;
import com.nemeles.core.economy.DualEconomyService;
import com.nemeles.core.economy.InterestManager;
import com.nemeles.core.economy.VaultEconomyBridge;
import com.nemeles.core.listener.ProfileListener;
import com.nemeles.core.profile.ProfileServiceImpl;
import com.nemeles.core.region.RegionFlags;
import com.nemeles.core.region.WorldGuardRegionService;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Plugin nucleo de NemelesRP. Milestone 1 (Fundacion): arranca la capa de datos (SQLite via HikariCP),
 * ejecuta las migraciones del core (crea las tablas) y publica la {@link NemelesApi}.
 *
 * <p>Siguientes milestones: economia dual (registro en Vault), perfiles, regiones (WorldGuard) y eventos.</p>
 */
public final class NemelesCorePlugin extends JavaPlugin {

    private HikariDatabaseProvider database;
    private InterestManager interest;

    @Override
    public void onLoad() {
        // Los flags custom de WorldGuard DEBEN registrarse en onLoad (antes de que WG arranque).
        try {
            Class.forName("com.sk89q.worldguard.WorldGuard");
            RegionFlags.register(getLogger());
        } catch (Throwable t) {
            getLogger().info("WorldGuard no presente en onLoad; regiones quedaran desactivadas.");
        }
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();

        // 1) Capa de datos + migraciones (debe ir antes de habilitar nada que use la BD).
        try {
            database = new HikariDatabaseProvider(this);
            database.registerMigrations("core", CoreMigrations.all());
            database.runMigrations();
            NemelesApi.registerDatabase(database);
        } catch (Throwable t) {
            getLogger().severe("No se pudo inicializar la base de datos: " + t.getMessage());
            t.printStackTrace();
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // 2) Servicios del nucleo (economia dual + perfiles) y su publicacion en la API.
        ProfileServiceImpl profiles = new ProfileServiceImpl(database, getLogger());
        DualEconomyService economy = new DualEconomyService(database, getLogger(), MoneyType.EFECTIVO);
        NemelesApi.registerProfiles(profiles);
        NemelesApi.registerEconomy(economy);
        getServer().getPluginManager().registerEvents(new ProfileListener(this, profiles, economy), this);

        // Interes de ahorros (saldo BANCO): paga un % cada 24h con topes anti-abuso (ver config economy.interest).
        interest = new InterestManager(this, economy);
        interest.start();

        // 3) Regiones (WorldGuard) si esta disponible.
        if (getServer().getPluginManager().getPlugin("WorldGuard") != null && RegionFlags.PERMADEATH != null) {
            try {
                RegionService regions = new WorldGuardRegionService(RegionFlags.PERMADEATH, RegionFlags.ALLOW_CRIME);
                NemelesApi.registerRegions(regions);
                getLogger().info("RegionService (WorldGuard) activo: safezones + Zona Negra disponibles.");
            } catch (Throwable t) {
                getLogger().warning("No se pudo iniciar RegionService: " + t.getMessage());
            }
        } else {
            getLogger().info("WorldGuard no detectado: RegionService desactivado.");
        }

        // 4) Puente Vault (telefono/tiendas/EssentialsX ven el saldo del core).
        if (getServer().getPluginManager().getPlugin("Vault") != null) {
            try {
                getServer().getServicesManager().register(net.milkbowl.vault.economy.Economy.class,
                        new VaultEconomyBridge(this, economy), this, ServicePriority.Highest);
                getLogger().info("Economia registrada en Vault (proveedor NemelesCore, prioridad HIGHEST).");
            } catch (Throwable t) {
                getLogger().warning("No se pudo registrar el puente Vault: " + t.getMessage());
            }
        } else {
            getLogger().info("Vault no detectado: puente economico desactivado.");
        }

        // 5) PlaceholderAPI: %nemeles_efectivo/banco/sucio/limpio% para scoreboard/TAB.
        if (getServer().getPluginManager().getPlugin("PlaceholderAPI") != null) {
            try {
                new com.nemeles.core.papi.NemelesPlaceholders(this, economy, interest).register();
                getLogger().info("Placeholders %nemeles_*% registrados en PlaceholderAPI.");
            } catch (Throwable t) {
                getLogger().warning("No se pudieron registrar los placeholders: " + t.getMessage());
            }
        }

        // 6) Comandos de administracion.
        PluginCommand cmd = getCommand("nemeles");
        if (cmd != null) {
            cmd.setExecutor(new NemelesCommand(this, database, economy, profiles));
        }
        PluginCommand cajero = getCommand("cajero");
        if (cajero != null) {
            cajero.setExecutor(new com.nemeles.core.command.CashierCommand(this, economy));
        }
        // Pago "de calle": entregar efectivo en mano a un jugador cercano (transfer atomico anti-dupe).
        PluginCommand pagar = getCommand("pagar");
        if (pagar != null) {
            com.nemeles.core.command.PayCommand pay = new com.nemeles.core.command.PayCommand(this, economy);
            pagar.setExecutor(pay);
            pagar.setTabCompleter(pay);
        }
        PluginCommand mercadonegro = getCommand("mercadonegro");
        if (mercadonegro != null) {
            mercadonegro.setExecutor(new com.nemeles.core.command.BlackMarketCommand(this));
        }
        // Menu de ayuda crossplay (onboarding): chat clicable en Java y Bedrock; SimpleForm si hay Floodgate.
        PluginCommand ayuda = getCommand("ayuda");
        if (ayuda != null) {
            com.nemeles.core.command.HelpCommand help = new com.nemeles.core.command.HelpCommand(this);
            ayuda.setExecutor(help);
            ayuda.setTabCompleter(help);
        }

        getLogger().info("NemelesCore habilitado. Motor de BD: " + (database.isSqlite() ? "SQLite" : "MySQL")
                + ". Economia dual + perfiles activos. Fundacion lista.");
    }

    @Override
    public void onDisable() {
        if (interest != null) {
            interest.stop();
            interest = null;
        }
        NemelesApi.clear();
        if (database != null) {
            database.shutdown();
            database = null;
        }
        getLogger().info("NemelesCore deshabilitado. Base de datos cerrada.");
    }

    public HikariDatabaseProvider database() {
        return database;
    }
}
