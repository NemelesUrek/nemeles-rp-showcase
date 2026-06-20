package com.nemeles.jobs;

import com.nemeles.core.api.NemelesApi;
import com.nemeles.core.api.db.DatabaseProvider;
import com.nemeles.jobs.command.JobsCommand;
import com.nemeles.jobs.command.PerkCommand;
import com.nemeles.jobs.perk.PerkRegistry;
import com.nemeles.jobs.perk.PerksDao;
import com.nemeles.jobs.db.JobsDao;
import com.nemeles.jobs.db.JobsMigrations;
import com.nemeles.jobs.decay.DecayService;
import com.nemeles.jobs.level.LevelCurve;
import com.nemeles.jobs.listener.JobsActivityListener;
import com.nemeles.jobs.listener.JobsBlockListener;
import com.nemeles.jobs.listener.JobsConnectionListener;
import com.nemeles.jobs.weed.WeedCommand;
import com.nemeles.jobs.weed.WeedDao;
import com.nemeles.jobs.weed.WeedItems;
import com.nemeles.jobs.weed.WeedListener;
import com.nemeles.jobs.weed.WeedManager;
import com.nemeles.jobs.employment.EmploymentCommand;
import com.nemeles.jobs.employment.EmploymentDao;
import com.nemeles.jobs.employment.EmploymentManager;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/** Modulo de trabajos (jobs) con niveles. Consume la API de NemelesCore. */
public final class JobsPlugin extends JavaPlugin {

    private JobManager jobManager;
    private WeedManager weed;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        if (!NemelesApi.isReady()) {
            getLogger().severe("NemelesCore no esta listo; deshabilitando NemelesJobs.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        DatabaseProvider db = NemelesApi.database();
        // Registra (y aplica al instante) las migraciones del namespace "jobs".
        db.registerMigrations("jobs", JobsMigrations.all());

        FileConfiguration cfg = getConfig();
        LevelCurve curve = new LevelCurve(
                cfg.getInt("levels.base", 100),
                cfg.getInt("levels.quad", 4),
                cfg.getInt("levels.cap", 100),
                cfg.getDouble("pay.scale-per-level", 0.03));
        JobsDao dao = new JobsDao(db.dataSource());
        JobRegistry registry = new JobRegistry();
        DecayService decay = new DecayService(curve,
                cfg.getBoolean("decay.enabled", true),
                cfg.getLong("decay.grace-days", 3),
                cfg.getDouble("decay.rate-per-day", 0.015),
                cfg.getLong("decay.cap-days", 30),
                cfg.getInt("decay.floor-level", 10),
                cfg.getDouble("decay.illegal-mult", 1.5),
                cfg.getInt("decay.max-drop-per-login", 5));
        EmploymentManager employment = new EmploymentManager(this, new EmploymentDao(db.dataSource()), db.dbExecutor(),
                registry,
                cfg.getLong("employment.change-cooldown-hours", 24) * 3_600_000L,
                cfg.getDouble("employment.no-job-xp-factor", 0.35),
                cfg.getDouble("employment.off-job-xp-factor", 0.5));
        PerkRegistry perks = new PerkRegistry();
        PerksDao perksDao = new PerksDao(db.dataSource());
        jobManager = new JobManager(this, registry, curve, dao, db.dbExecutor(), decay, employment, perks, perksDao);
        // Zonas de trabajo: bonus de XP dentro de la zona de tu oficio; penalización fuera (especialización).
        com.nemeles.jobs.zone.WorkZoneManager workZones = new com.nemeles.jobs.zone.WorkZoneManager(cfg);
        jobManager.setWorkZones(workZones);
        // Expone la API de habilidades para otros modulos (p.ej. combate sube "medic" al reanimar).
        try { NemelesApi.registerSkills(jobManager); }
        catch (Throwable t) { getLogger().warning("No se pudo registrar SkillService: " + t.getMessage()); }

        // Carga a los jugadores ya conectados (por si se recarga el plugin con gente dentro).
        for (Player p : Bukkit.getOnlinePlayers()) {
            jobManager.loadPlayer(p.getUniqueId());
            employment.load(p.getUniqueId());
        }

        getServer().getPluginManager().registerEvents(new JobsBlockListener(jobManager), this);
        getServer().getPluginManager().registerEvents(new JobsActivityListener(jobManager), this);
        getServer().getPluginManager().registerEvents(new JobsConnectionListener(jobManager, employment), this);

        PluginCommand cmd = getCommand("jobs");
        if (cmd != null) {
            cmd.setExecutor(new JobsCommand(jobManager));
        }
        PluginCommand empleoCmd = getCommand("empleo");
        if (empleoCmd != null) {
            empleoCmd.setExecutor(new EmploymentCommand(employment, workZones));
        }

        // Avisos de entrada/salida de zona de trabajo (cada 1 s, solo jugadores online).
        if (workZones.enabled() && !workZones.zones().isEmpty()) {
            getServer().getScheduler().runTaskTimer(this, () -> {
                for (Player p : Bukkit.getOnlinePlayers()) {
                    try { workZones.tickPlayer(p, employment.getJob(p.getUniqueId())); } catch (Throwable ignored) { }
                }
            }, 60L, 20L);
            getLogger().info("Zonas de trabajo activas: " + workZones.zones().size() + ".");
        }
        PluginCommand perkCmd = getCommand("perk");
        if (perkCmd != null) {
            perkCmd.setExecutor(new PerkCommand(jobManager, perks));
        }

        // Marihuana (rama ilegal de Agricultura).
        WeedItems weedItems = new WeedItems(this);
        WeedDao weedDao = new WeedDao(db.dataSource());
        weed = new WeedManager(this, weedDao, db.dbExecutor(), weedItems, jobManager, cfg);
        weed.load();
        getServer().getPluginManager().registerEvents(new WeedListener(weed), this);
        PluginCommand weedCmd = getCommand("weed");
        if (weedCmd != null) {
            weedCmd.setExecutor(new WeedCommand(weed));
        }

        getLogger().info("NemelesJobs habilitado. Habilidades: " + registry.all().size() + " + marihuana.");
    }

    @Override
    public void onDisable() {
        if (jobManager != null) {
            jobManager.saveAllOnline();
        }
        if (weed != null) {
            weed.stop();
        }
    }
}
