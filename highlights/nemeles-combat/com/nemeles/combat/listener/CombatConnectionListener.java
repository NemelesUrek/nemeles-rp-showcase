package com.nemeles.combat.listener;

import com.nemeles.combat.CombatConfig;
import com.nemeles.combat.CombatKeys;
import com.nemeles.combat.CombatTagManager;
import com.nemeles.combat.Contraband;
import com.nemeles.combat.DownedManager;
import com.nemeles.combat.db.DeathDao;
import com.nemeles.core.api.NemelesApi;
import com.nemeles.core.api.economy.MoneyType;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;
import java.util.concurrent.Executor;

/** Combat-logging (desconectar derribado/en combate = muerte offline), inmunidad al reaparecer, limpieza. */
public final class CombatConnectionListener implements Listener {

    private final CombatConfig cfg;
    private final CombatKeys keys;
    private final DownedManager downed;
    private final CombatTagManager tags;
    private final DeathDao deathDao;
    private final Executor dbExecutor;

    private com.nemeles.combat.render.GunModelService gunModel;   // modelo 3D del arma (opcional, soft-dep)
    public void setGunModel(com.nemeles.combat.render.GunModelService gunModel) { this.gunModel = gunModel; }

    public CombatConnectionListener(CombatConfig cfg, CombatKeys keys, DownedManager downed, CombatTagManager tags,
                                    DeathDao deathDao, Executor dbExecutor) {
        this.cfg = cfg;
        this.keys = keys;
        this.downed = downed;
        this.tags = tags;
        this.deathDao = deathDao;
        this.dbExecutor = dbExecutor;
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        if (gunModel != null) gunModel.cleanup(p);   // quitar tracker/modelo del arma al salir (sin fantasmas)
        UUID id = p.getUniqueId();
        boolean wasDowned = downed.isDowned(id);
        boolean penalize = wasDowned || tags.inCombat(id);
        // Desconectar derribado = muerte offline. Restaurar el estado ANTES de que se guarde el jugador
        // (si no, se persiste congelado con SLOWNESS 250 + walkSpeed 0 y reaparece inmóvil).
        if (wasDowned) downed.restorePlayer(p);
        // Combat-logging pierde el contrabando igual que al morir (sumidero), no solo el dinero sucio.
        if (penalize) Contraband.strip(p, keys.contraband, false);
        downed.removeSession(id);
        tags.clear(id);
        if (penalize) applyOfflineDeath(id, p.getLocation());
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        downed.grantImmunity(id, cfg.respawnImmunitySeconds);
        downed.clearPending(id); // limpia cualquier flag de muerte pendiente al reaparecer
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        // red de seguridad: limpiar restos de un downed mal cerrado (SLOWNESS 250 + velocidades a 0)
        // y cualquier flag de permadeath que hubiera quedado pegado.
        downed.restorePlayer(p);
        downed.clearPending(p.getUniqueId());
    }

    private void applyOfflineDeath(UUID id, Location loc) {
        try {
            NemelesApi.economy().balance(id, MoneyType.SUCIO).thenAccept(bal -> {
                long lostCents = 0;
                if (bal != null && bal.signum() > 0 && cfg.deathDirtyPct > 0) {
                    BigDecimal lose = bal.multiply(BigDecimal.valueOf(cfg.deathDirtyPct)).movePointLeft(2).setScale(2, RoundingMode.HALF_UP);
                    if (lose.signum() > 0) {
                        NemelesApi.economy().withdraw(id, MoneyType.SUCIO, lose, "death:combatlog");
                        lostCents = lose.movePointRight(2).longValue();
                    }
                }
                final long fc = lostCents;
                String world = loc.getWorld() != null ? loc.getWorld().getName() : null;
                int x = loc.getBlockX(), y = loc.getBlockY(), z = loc.getBlockZ();
                long at = System.currentTimeMillis();
                dbExecutor.execute(() -> {
                    try { deathDao.insert(id, null, "COMBATLOG", false, fc, world, x, y, z, at); }
                    catch (Exception ignored) { }
                });
            });
        } catch (Throwable ignored) { }
    }
}
