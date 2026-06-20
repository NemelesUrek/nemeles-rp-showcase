package com.nemeles.jobs.listener;

import com.nemeles.jobs.JobManager;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.inventory.ItemStack;

import java.util.EnumMap;
import java.util.Map;

/**
 * PESCADOR (PlayerFishEvent) y CAZADOR (EntityDeathEvent): pagan/XP por captura/pieza, respetando el
 * sistema de empleo (solo paga si estás empleado en ese oficio; si no, practicas con XP reducida).
 * Estas dos habilidades NO usan acciones de bloque, por eso van por aquí y no por JobsBlockListener.
 */
public final class JobsActivityListener implements Listener {

    private final JobManager jobs;

    /** Pez capturado -> [pago, xp]. Captura desconocida (tesoro/otros) usa el valor genérico de abajo. */
    private static final Map<Material, double[]> FISH = new EnumMap<>(Material.class);
    static {
        FISH.put(Material.COD,           new double[]{ 8,  6 });
        FISH.put(Material.SALMON,        new double[]{ 11, 7 });
        FISH.put(Material.PUFFERFISH,    new double[]{ 16, 9 });
        FISH.put(Material.TROPICAL_FISH, new double[]{ 28, 12 });
    }

    /** Fauna cazable -> [pago, xp]. Solo estas entidades pagan (ganado/caza menor y algún animal salvaje). */
    private static final Map<EntityType, double[]> GAME = new EnumMap<>(EntityType.class);
    static {
        GAME.put(EntityType.CHICKEN, new double[]{ 4,  4 });
        GAME.put(EntityType.SHEEP,   new double[]{ 5,  5 });
        GAME.put(EntityType.PIG,     new double[]{ 6,  6 });
        GAME.put(EntityType.COW,     new double[]{ 6,  6 });
        GAME.put(EntityType.RABBIT,  new double[]{ 7,  6 });
        GAME.put(EntityType.FOX,     new double[]{ 12, 9 });
        GAME.put(EntityType.GOAT,    new double[]{ 10, 8 });
        GAME.put(EntityType.POLAR_BEAR, new double[]{ 18, 12 });
    }

    public JobsActivityListener(JobManager jobs) { this.jobs = jobs; }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFish(PlayerFishEvent e) {
        if (e.getState() != PlayerFishEvent.State.CAUGHT_FISH) return;
        Player p = e.getPlayer();
        if (p.getGameMode() == GameMode.CREATIVE) return;
        Material caught = Material.COD;
        if (e.getCaught() instanceof Item it) {
            ItemStack st = it.getItemStack();
            if (st != null) caught = st.getType();
        }
        double[] v = FISH.getOrDefault(caught, new double[]{ 6, 5 });
        jobs.handleCatch(p, "fisher", v[0], (int) v[1], "fish:" + caught.name().toLowerCase());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onKill(EntityDeathEvent e) {
        Player killer = e.getEntity().getKiller();
        if (killer == null || killer.getGameMode() == GameMode.CREATIVE) return;
        double[] v = GAME.get(e.getEntityType());
        if (v == null) return;
        jobs.handleCatch(killer, "hunter", v[0], (int) v[1], "hunt:" + e.getEntityType().name().toLowerCase());
    }
}
