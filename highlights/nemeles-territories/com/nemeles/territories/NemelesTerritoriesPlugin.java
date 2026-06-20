package com.nemeles.territories;

import com.nemeles.core.api.NemelesApi;
import com.nemeles.core.api.db.DatabaseProvider;
import com.nemeles.core.api.faction.FactionLifecycleEvent;
import com.nemeles.territories.command.TerritoryCommands;
import com.nemeles.territories.db.TerritoriesDao;
import com.nemeles.territories.db.TerritoriesMigrations;
import com.nemeles.territories.listener.TerritoryMoveListener;
import com.nemeles.territories.papi.TerritoryPlaceholders;
import org.bukkit.command.PluginCommand;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;

public final class NemelesTerritoriesPlugin extends JavaPlugin {

    private TerritoryServiceImpl svc;
    private TerritoryManager mgr;
    private WarManager wars;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        if (!NemelesApi.isReady()) {
            getLogger().severe("NemelesCore no esta listo; deshabilitando NemelesTerritories.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        DatabaseProvider db = NemelesApi.database();
        db.registerMigrations("territory", TerritoriesMigrations.all());

        TerrConfig cfg = TerrConfig.from(getConfig());
        TerritoriesDao dao = new TerritoriesDao(db.dataSource());
        svc = new TerritoryServiceImpl(this, dao, db.dbExecutor(), cfg);
        wars = new WarManager(this, svc, cfg, dao, db.dbExecutor());
        mgr = new TerritoryManager(this, svc, cfg, wars);
        svc.setManager(mgr);
        svc.load();
        wars.load(); // reconcilia guerras colgadas (devuelve el escrow al atacante)
        NemelesApi.registerTerritories(svc);

        getServer().getPluginManager().registerEvents(new TerritoryMoveListener(mgr), this);
        // Disolucion de mafia a mitad de guerra: cancela la guerra (refund del escrow) y libera sus zonas.
        getServer().getPluginManager().registerEvents(new FactionLifecycleListener(mgr), this);
        TerritoryCommands cmds = new TerritoryCommands(this, svc, mgr);
        setExecutor("territorio", cmds);
        setExecutor("turf", cmds);

        if (getServer().getPluginManager().getPlugin("PlaceholderAPI") != null) {
            try {
                new TerritoryPlaceholders(this, svc).register();
                getLogger().info("Placeholders %territory_*% registrados en PlaceholderAPI.");
            } catch (Throwable t) {
                getLogger().warning("No se pudieron registrar placeholders de territorios: " + t.getMessage());
            }
        }

        long contestTicks = Math.max(20L, (long) cfg.tickSeconds * 20L);
        getServer().getScheduler().runTaskTimer(this, mgr::contestTick, contestTicks, contestTicks);
        getServer().getScheduler().runTaskTimer(this, mgr::economyTick, 1200L, 1200L); // cada 60s

        getLogger().info("NemelesTerritories habilitado. Turf wars activas.");
    }

    @Override
    public void onDisable() {
        if (mgr != null) mgr.shutdown(); // quita las BossBars de captura para que no queden fantasma
        if (wars != null) wars.shutdown(); // limpia cache; guerras abiertas se reconcilian al arrancar
        if (svc != null) svc.flushAll();
    }

    private void setExecutor(String name, TerritoryCommands cmds) {
        PluginCommand c = getCommand(name);
        if (c != null) c.setExecutor(cmds);
    }

    /** Reacciona a la disolucion de una mafia: cancela sus guerras y libera sus territorios. */
    private static final class FactionLifecycleListener implements Listener {
        private final TerritoryManager mgr;
        FactionLifecycleListener(TerritoryManager mgr) { this.mgr = mgr; }

        @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
        public void onLifecycle(FactionLifecycleEvent e) {
            if (e.kind() == FactionLifecycleEvent.Kind.DISBANDED) {
                try { mgr.handleFactionDisbanded(e.factionId()); } catch (Throwable ignored) { }
            }
        }
    }
}
