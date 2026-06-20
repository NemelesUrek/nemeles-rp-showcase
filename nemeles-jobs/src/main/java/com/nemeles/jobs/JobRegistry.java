package com.nemeles.jobs;

import com.nemeles.jobs.model.JobAction;
import com.nemeles.jobs.model.JobCategory;
import com.nemeles.jobs.model.JobDefinition;
import org.bukkit.Material;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Trabajos disponibles (MVP: Minero y Granjero legal, con tiers por nivel).
 * Los valores estan calibrados conservadores; mas adelante pueden moverse a YAML.
 */
public final class JobRegistry {

    private final Map<String, JobDefinition> jobs = new LinkedHashMap<>();

    public JobRegistry() {
        register(buildMiner());
        register(buildFarmer());
        register(buildMedic());
        register(buildCourier());
        register(buildLumberjack());
        register(buildFisher());
        register(buildHunter());
    }

    private void register(JobDefinition def) {
        jobs.put(def.id(), def);
    }

    public JobDefinition get(String id) { return jobs.get(id); }
    public boolean exists(String id) { return jobs.containsKey(id); }
    public Collection<JobDefinition> all() { return jobs.values(); }

    // ─── MINERO ──────────────────────────────────────────────
    private static JobDefinition buildMiner() {
        JobDefinition d = new JobDefinition("miner", "Minero", JobCategory.RECOLECCION);
        d.add(new JobAction(Material.STONE, 0.40, 3, 1));
        d.add(new JobAction(Material.COAL_ORE, 0.80, 6, 1));
        d.add(new JobAction(Material.DEEPSLATE_COAL_ORE, 0.90, 6, 1));
        d.add(new JobAction(Material.COPPER_ORE, 1.20, 8, 10));
        d.add(new JobAction(Material.DEEPSLATE_COPPER_ORE, 1.30, 8, 10));
        d.add(new JobAction(Material.IRON_ORE, 1.50, 10, 10));
        d.add(new JobAction(Material.DEEPSLATE_IRON_ORE, 1.60, 10, 10));
        d.add(new JobAction(Material.REDSTONE_ORE, 2.50, 14, 25));
        d.add(new JobAction(Material.DEEPSLATE_REDSTONE_ORE, 2.60, 14, 25));
        d.add(new JobAction(Material.LAPIS_ORE, 2.80, 14, 25));
        d.add(new JobAction(Material.DEEPSLATE_LAPIS_ORE, 2.90, 14, 25));
        d.add(new JobAction(Material.GOLD_ORE, 3.00, 16, 25));
        d.add(new JobAction(Material.DEEPSLATE_GOLD_ORE, 3.20, 16, 25));
        d.add(new JobAction(Material.DIAMOND_ORE, 6.00, 28, 40));
        d.add(new JobAction(Material.DEEPSLATE_DIAMOND_ORE, 6.50, 28, 40));
        d.add(new JobAction(Material.EMERALD_ORE, 9.00, 40, 60));
        d.add(new JobAction(Material.DEEPSLATE_EMERALD_ORE, 9.50, 40, 60));
        d.add(new JobAction(Material.ANCIENT_DEBRIS, 15.00, 55, 80));
        return d;
    }

    // ─── MEDICO (habilidad de servicio: sube reanimando; sin acciones de bloque) ──
    private static JobDefinition buildMedic() {
        return new JobDefinition("medic", "Médico", JobCategory.SERVICIO);
    }

    // ─── REPARTIDOR (servicio: misiones NPC; sin acciones de bloque) ──
    private static JobDefinition buildCourier() {
        return new JobDefinition("courier", "Repartidor", JobCategory.SERVICIO);
    }

    // ─── LEÑADOR (tala de troncos; usa BlockBreakEvent como minero/granjero) ──
    private static JobDefinition buildLumberjack() {
        JobDefinition d = new JobDefinition("lumberjack", "Leñador", JobCategory.RECOLECCION);
        d.add(new JobAction(Material.OAK_LOG, 0.60, 5, 1));
        d.add(new JobAction(Material.BIRCH_LOG, 0.60, 5, 1));
        d.add(new JobAction(Material.SPRUCE_LOG, 0.65, 5, 1));
        d.add(new JobAction(Material.JUNGLE_LOG, 0.70, 6, 8));
        d.add(new JobAction(Material.ACACIA_LOG, 0.70, 6, 8));
        d.add(new JobAction(Material.DARK_OAK_LOG, 0.75, 6, 8));
        d.add(new JobAction(Material.MANGROVE_LOG, 0.75, 6, 15));
        d.add(new JobAction(Material.CHERRY_LOG, 0.85, 7, 20));
        return d;
    }

    // ─── PESCADOR (sin acciones de bloque: paga por captura via JobsActivityListener/PlayerFishEvent) ──
    private static JobDefinition buildFisher() {
        return new JobDefinition("fisher", "Pescador", JobCategory.RECOLECCION);
    }

    // ─── CAZADOR (sin acciones de bloque: paga por pieza via JobsActivityListener/EntityDeathEvent) ──
    private static JobDefinition buildHunter() {
        return new JobDefinition("hunter", "Cazador", JobCategory.RECOLECCION);
    }

    // ─── GRANJERO (legal; la rama de drogas llega en una fase posterior) ──
    private static JobDefinition buildFarmer() {
        JobDefinition d = new JobDefinition("farmer", "Granjero", JobCategory.AGRICULTURA);
        d.add(new JobAction(Material.WHEAT, 0.50, 4, 1));
        d.add(new JobAction(Material.CARROTS, 0.70, 5, 8));
        d.add(new JobAction(Material.POTATOES, 0.70, 5, 8));
        d.add(new JobAction(Material.BEETROOTS, 1.00, 6, 15));
        d.add(new JobAction(Material.NETHER_WART, 2.00, 9, 30));
        return d;
    }
}
