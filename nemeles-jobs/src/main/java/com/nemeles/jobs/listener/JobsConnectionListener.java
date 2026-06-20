package com.nemeles.jobs.listener;

import com.nemeles.jobs.JobManager;
import com.nemeles.jobs.employment.EmploymentManager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/** Carga habilidades + empleo al entrar y los libera/guarda al salir. */
public final class JobsConnectionListener implements Listener {

    private final JobManager jobs;
    private final EmploymentManager employment;

    public JobsConnectionListener(JobManager jobs, EmploymentManager employment) {
        this.jobs = jobs;
        this.employment = employment;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        jobs.loadPlayer(e.getPlayer().getUniqueId());
        employment.load(e.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        jobs.unloadPlayer(e.getPlayer().getUniqueId());
        employment.evict(e.getPlayer().getUniqueId());
    }
}
