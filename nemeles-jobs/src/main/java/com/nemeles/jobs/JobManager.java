package com.nemeles.jobs;

import com.nemeles.core.api.NemelesApi;
import com.nemeles.core.api.economy.MoneyType;
import com.nemeles.core.api.skills.SkillService;
import com.nemeles.jobs.db.JobsDao;
import com.nemeles.jobs.decay.DecayService;
import com.nemeles.jobs.employment.EmploymentManager;
import com.nemeles.jobs.level.LevelCurve;
import com.nemeles.jobs.model.JobAction;
import com.nemeles.jobs.model.JobCategory;
import com.nemeles.jobs.model.JobDefinition;
import com.nemeles.jobs.perk.PerkChoice;
import com.nemeles.jobs.perk.PerkDef;
import com.nemeles.jobs.perk.PerkRegistry;
import com.nemeles.jobs.perk.PerksDao;
import org.bukkit.block.Block;
import org.bukkit.inventory.ItemStack;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

/**
 * Motor de HABILIDADES: todo jugador puede subir TODAS las habilidades practicando (sin "unirse").
 * Cache de progreso, pagos al core, XP, subida de nivel y decadencia por inactividad (al entrar).
 */
public final class JobManager implements SkillService {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();

    private final Plugin plugin;
    private final JobRegistry registry;
    private final LevelCurve curve;
    private final JobsDao dao;
    private final Executor dbExecutor;
    private final DecayService decay;
    private final EmploymentManager employment;
    private final PerkRegistry perks;
    private final PerksDao perksDao;
    private com.nemeles.jobs.zone.WorkZoneManager workZones;   // opcional; null = sin zonas (multiplicador 1.0)
    private final Map<UUID, PlayerJobs> cache = new ConcurrentHashMap<>();

    public JobManager(Plugin plugin, JobRegistry registry, LevelCurve curve,
                      JobsDao dao, Executor dbExecutor, DecayService decay, EmploymentManager employment,
                      PerkRegistry perks, PerksDao perksDao) {
        this.plugin = plugin;
        this.registry = registry;
        this.curve = curve;
        this.dao = dao;
        this.dbExecutor = dbExecutor;
        this.decay = decay;
        this.employment = employment;
        this.perks = perks;
        this.perksDao = perksDao;
    }

    public PerkRegistry perks() { return perks; }

    public void setWorkZones(com.nemeles.jobs.zone.WorkZoneManager wz) { this.workZones = wz; }

    /** Multiplicador de XP por zona de trabajo (1.0 si no hay zonas o el módulo está apagado). */
    private double zoneMult(Player p, String skillId) {
        if (workZones == null) return 1.0;
        try { return workZones.xpMultiplier(p, skillId); } catch (Throwable t) { return 1.0; }
    }

    public JobRegistry registry() { return registry; }
    public LevelCurve curve() { return curve; }
    public PlayerJobs get(UUID uuid) { return cache.get(uuid); }

    // ─── carga (con decadencia) / guardado ───────────────────
    public void loadPlayer(UUID uuid) {
        dbExecutor.execute(() -> {
            PlayerJobs pj;
            try {
                pj = dao.load(uuid);
            } catch (Exception e) {
                plugin.getLogger().warning("[SKILLS] Error cargando " + uuid + ": " + e.getMessage());
                cache.put(uuid, new PlayerJobs());
                return;
            }
            try { perksDao.loadInto(uuid, pj); } catch (Exception ex) { plugin.getLogger().warning("[PERKS] load: " + ex.getMessage()); }
            List<String> notices = new ArrayList<>();
            long now = System.currentTimeMillis();
            for (Map.Entry<String, JobProgress> e : pj.all().entrySet()) {
                JobDefinition def = registry.get(e.getKey());
                boolean illegal = def != null && def.category() == JobCategory.ILEGAL;
                int dropped = decay.apply(e.getValue(), now, illegal);
                if (dropped > 0) {
                    String name = def != null ? def.displayName() : e.getKey();
                    notices.add(name + " bajo a Lv" + e.getValue().level() + " por " + dropped
                            + " nivel(es) de inactividad.");
                }
            }
            cache.put(uuid, pj);
            if (!notices.isEmpty()) {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    Player p = Bukkit.getPlayer(uuid);
                    if (p != null) {
                        for (String n : notices) p.sendMessage(ChatColor.GRAY + "[Habilidades] " + ChatColor.YELLOW + n);
                    }
                });
            }
        });
    }

    public void unloadPlayer(UUID uuid) {
        PlayerJobs pj = cache.remove(uuid);
        if (pj != null) persist(uuid, pj);
    }

    public void saveAllOnline() {
        cache.forEach(this::persist);
    }

    private void persist(UUID uuid, PlayerJobs pj) {
        dbExecutor.execute(() -> {
            for (Map.Entry<String, JobProgress> e : pj.all().entrySet()) {
                JobProgress p = e.getValue();
                if (!p.dirty()) continue;
                try {
                    dao.upsert(uuid, e.getKey(), p);
                    p.clean();
                } catch (Exception ex) {
                    plugin.getLogger().warning("[SKILLS] Error guardando " + e.getKey() + ": " + ex.getMessage());
                }
            }
        });
    }

    // ─── nucleo de pago (habilidades abiertas) ───────────────
    public void handleBreak(Player player, Material material, boolean placed, boolean mature) {
        PlayerJobs pj = cache.get(player.getUniqueId());
        if (pj == null) return;
        for (JobDefinition def : registry.all()) {
            JobAction action = def.action(material);
            if (action == null) continue;
            if (def.category() == JobCategory.AGRICULTURA && !mature) continue; // solo cosecha madura
            if (placed) continue; // anti-farm: bloque colocado por jugador
            JobProgress prog = pj.computeIfAbsent(def.id()); // auto-crea la habilidad al practicar
            if (prog.level() < action.unlockLevel()) {
                actionBar(player, ChatColor.RED + "Necesitas " + def.displayName() + " nivel " + action.unlockLevel() + ".");
                continue;
            }
            award(player, def, prog, action);
        }
    }

    /**
     * Pago + XP por una ACTIVIDAD que no es romper bloque (pesca, caza). Respeta el empleo (paga solo si
     * estás empleado en ese oficio; si no, practicas con XP reducida sin pago), el nivel y la zona de trabajo.
     */
    public void handleCatch(Player player, String jobId, double basePayout, int baseXp, String reason) {
        PlayerJobs pj = cache.get(player.getUniqueId());
        if (pj == null) return;
        JobDefinition def = registry.get(jobId);
        if (def == null) return;
        UUID uuid = player.getUniqueId();
        JobProgress prog = pj.computeIfAbsent(jobId);
        int xp = Math.max(1, (int) Math.round(baseXp * employment.xpFactor(uuid, jobId) * zoneMult(player, jobId)));
        if (employment.paysMoneyForSkill(uuid, jobId)) {
            double pay = round2(basePayout * curve.payMultiplier(prog.level()));
            NemelesApi.economy()
                    .deposit(uuid, MoneyType.EFECTIVO, BigDecimal.valueOf(pay), "skill:" + jobId + ":" + reason)
                    .thenAccept(res -> {
                        if (res == null || !res.success()) return;
                        Bukkit.getScheduler().runTask(plugin, () -> applyXp(player, def, prog, xp, pay, true));
                    });
        } else {
            applyXp(player, def, prog, xp, 0, false);
        }
    }

    private void award(Player player, JobDefinition def, JobProgress prog, JobAction action) {
        UUID uuid = player.getUniqueId();
        int xp = Math.max(1, (int) Math.round(action.xp() * employment.xpFactor(uuid, def.id()) * zoneMult(player, def.id())));
        if (employment.paysMoneyForSkill(uuid, def.id())) {
            double pay = round2(action.payout() * curve.payMultiplier(prog.level()));
            NemelesApi.economy()
                    .deposit(uuid, MoneyType.EFECTIVO, BigDecimal.valueOf(pay), "skill:" + def.id() + ":" + action.material())
                    .thenAccept(res -> {
                        if (res == null || !res.success()) return;
                        Bukkit.getScheduler().runTask(plugin, () -> applyXp(player, def, prog, xp, pay, true));
                    });
        } else {
            // sin empleo en esta actividad: practicas (XP reducida) pero sin pago
            applyXp(player, def, prog, xp, 0, false);
        }
    }

    private void applyXp(Player player, JobDefinition def, JobProgress prog, int xpGain, double pay, boolean paid) {
        prog.touch(System.currentTimeMillis());
        prog.addXpAndTotal(xpGain);
        boolean leveled = false;
        while (prog.level() < curve.cap() && prog.xp() >= curve.xpToNext(prog.level())) {
            prog.setXp(prog.xp() - curve.xpToNext(prog.level()));
            prog.setLevel(prog.level() + 1);
            leveled = true;
        }
        if (prog.level() > prog.peakLevel()) prog.setPeakLevel(prog.level());
        if (leveled) {
            title(player, ChatColor.GREEN + "¡Nivel " + prog.level() + "!", ChatColor.GRAY + def.displayName());
            player.sendMessage(ChatColor.GREEN + "[Habilidades] Subiste a " + ChatColor.WHITE + def.displayName()
                    + " nivel " + prog.level() + ChatColor.GREEN + ".");
            if (prog.level() % 10 == 0 && perks.tierDefined(def.id(), prog.level())) {
                PlayerJobs pj2 = cache.get(player.getUniqueId());
                if (pj2 != null && pj2.perk(def.id(), prog.level()) == '\0') {
                    player.sendMessage(ChatColor.LIGHT_PURPLE + "[Perk] ¡Nivel " + prog.level() + " en " + def.displayName()
                            + "! Elige una mejora: " + ChatColor.YELLOW + "/perk elegir " + def.id() + " " + prog.level() + " A|B"
                            + ChatColor.GRAY + " (ver /perk)");
                }
            }
        } else {
            long need = curve.xpToNext(prog.level());
            if (paid) {
                actionBar(player, ChatColor.GREEN + "+$" + fmt(pay) + ChatColor.GRAY + "   " + def.displayName()
                        + " Lv" + prog.level() + " (" + prog.xp() + "/" + need + " XP)");
            } else {
                actionBar(player, ChatColor.GREEN + "+" + xpGain + " XP " + ChatColor.GRAY + def.displayName()
                        + " Lv" + prog.level() + ChatColor.DARK_GRAY + " (sin empleo)");
            }
        }
    }

    /** Nivel actual de un jugador en una habilidad (1 si no la ha practicado). */
    public int getLevel(UUID uuid, String skillId) {
        PlayerJobs pj = cache.get(uuid);
        if (pj == null) return 1;
        JobProgress p = pj.get(skillId);
        return p == null ? 1 : p.level();
    }

    /** Concede XP a una habilidad SIN pago (p.ej. cultivar marihuana da XP de Agricultura). */
    public void grantXp(Player player, String skillId, int xp) {
        PlayerJobs pj = cache.get(player.getUniqueId());
        if (pj == null) return;
        JobDefinition def = registry.get(skillId);
        if (def == null) return;
        JobProgress prog = pj.computeIfAbsent(skillId);
        int scaled = Math.max(1, (int) Math.round(xp * employment.xpFactor(player.getUniqueId(), skillId) * zoneMult(player, skillId)));
        applyXp(player, def, prog, scaled, 0, false);
    }

    /** ADMIN: fija el nivel de una habilidad de un jugador (para pruebas). */
    public boolean adminSetLevel(Player player, String skillId, int level) {
        if (!registry.exists(skillId)) return false;
        PlayerJobs pj = cache.get(player.getUniqueId());
        if (pj == null) return false;
        int lvl = Math.max(1, Math.min(curve.cap(), level));
        JobProgress prog = pj.computeIfAbsent(skillId);
        prog.setLevel(lvl);
        prog.setXp(0);
        if (lvl > prog.peakLevel()) prog.setPeakLevel(lvl);
        return true;
    }

    // ─── perks (SkillService) ────────────────────────────────
    @Override
    public boolean hasPerk(UUID uuid, String perkId) {
        PerkChoice c = perks.choice(perkId);
        if (c == null) return false;
        PlayerJobs pj = cache.get(uuid);
        return pj != null && pj.perk(c.skillId, c.tier) == c.option;
    }

    @Override
    public char perkChoice(UUID uuid, String skill, int tier) {
        PlayerJobs pj = cache.get(uuid);
        return pj == null ? '\0' : pj.perk(skill, tier);
    }

    @Override
    public double perkValue(UUID uuid, String skill, String effectKey) {
        PlayerJobs pj = cache.get(uuid);
        if (pj == null) return 0.0;
        double sum = 0.0;
        for (Map.Entry<Integer, Character> e : pj.perksOf(skill).entrySet()) {
            PerkDef d = perks.get(skill, e.getKey());
            if (d == null) continue;
            PerkChoice c = d.byOption(e.getValue());
            if (c != null) sum += c.effect(effectKey);
        }
        return sum;
    }

    /** Hitos alcanzados (por peak_level) aún sin elegir. */
    public List<Integer> pendingPerkTiers(UUID uuid, String skill) {
        PlayerJobs pj = cache.get(uuid);
        if (pj == null) return new ArrayList<>();
        JobProgress p = pj.get(skill);
        int peak = p == null ? 1 : p.peakLevel();
        List<Integer> out = new ArrayList<>();
        for (Integer tier : perks.tiers(skill).keySet()) {
            if (tier <= peak && pj.perk(skill, tier) == '\0') out.add(tier);
        }
        return out;
    }

    public boolean choosePerk(Player p, String skill, int tier, char opt) {
        PlayerJobs pj = cache.get(p.getUniqueId());
        if (pj == null) return false;
        PerkDef def = perks.get(skill, tier);
        if (def == null) { p.sendMessage(ChatColor.RED + "Ese hito de perk no existe."); return false; }
        JobProgress pr = pj.get(skill);
        int peak = pr == null ? 1 : pr.peakLevel();
        if (tier > peak) { p.sendMessage(ChatColor.RED + "Aún no has alcanzado nivel " + tier + " en " + skill + "."); return false; }
        if (pj.perk(skill, tier) != '\0') { p.sendMessage(ChatColor.RED + "Ya elegiste el perk de nivel " + tier + "."); return false; }
        PerkChoice c = def.byOption(opt);
        if (c == null) { p.sendMessage(ChatColor.RED + "Opción inválida (A o B)."); return false; }
        pj.putPerk(skill, tier, opt);
        UUID u = p.getUniqueId();
        dbExecutor.execute(() -> { try { perksDao.save(u, skill, tier, opt); } catch (Exception ex) { plugin.getLogger().warning("[PERKS] save: " + ex.getMessage()); } });
        p.sendMessage(ChatColor.GREEN + "[Perk] Elegiste " + ChatColor.WHITE + c.displayName + ChatColor.GREEN + ".");
        return true;
    }

    /** Aplica perks de cosecha (doble-drop minería, cosecha extra legal) al romper un bloque pagado. */
    public void applyHarvestPerks(Player player, Material mat, boolean mature, Block block, ItemStack tool) {
        UUID uuid = player.getUniqueId();
        if (cache.get(uuid) == null) return;
        JobDefinition miner = registry.get("miner");
        if (miner != null && miner.action(mat) != null) {
            double chance = Math.min(0.35, perkValue(uuid, "miner", "mining.double_drop_chance"));
            if (chance > 0 && java.util.concurrent.ThreadLocalRandom.current().nextDouble() < chance) dropCopies(block, tool);
            if (isRareOre(mat)) {
                double rare = perkValue(uuid, "miner", "mining.bonus_rare");
                if (rare > 0 && java.util.concurrent.ThreadLocalRandom.current().nextDouble() < rare) dropCopies(block, tool);
            }
        }
        JobDefinition farmer = registry.get("farmer");
        if (mature && farmer != null && farmer.action(mat) != null) {
            double extra = perkValue(uuid, "farmer", "farm.legal_extra");
            if (extra > 0 && java.util.concurrent.ThreadLocalRandom.current().nextDouble() < extra) dropCopies(block, tool);
        }
    }

    private static void dropCopies(Block block, ItemStack tool) {
        if (block.getWorld() == null) return;
        for (ItemStack drop : (tool != null ? block.getDrops(tool) : block.getDrops())) {
            block.getWorld().dropItemNaturally(block.getLocation().add(0.5, 0.5, 0.5), drop);
        }
    }

    private static boolean isRareOre(Material m) {
        return m == Material.DIAMOND_ORE || m == Material.DEEPSLATE_DIAMOND_ORE
                || m == Material.EMERALD_ORE || m == Material.DEEPSLATE_EMERALD_ORE
                || m == Material.ANCIENT_DEBRIS;
    }

    // ─── ranking ─────────────────────────────────────────────
    public void showTop(Player player, String jobId, int limit) {
        JobDefinition def = registry.get(jobId);
        if (def == null) { player.sendMessage(ChatColor.RED + "[Habilidades] Esa habilidad no existe."); return; }
        dbExecutor.execute(() -> {
            try {
                List<String[]> rows = dao.top(jobId, limit);
                Bukkit.getScheduler().runTask(plugin, () -> {
                    player.sendMessage(ChatColor.AQUA + "== Top " + def.displayName() + " ==");
                    int i = 1;
                    for (String[] r : rows) {
                        player.sendMessage(ChatColor.GRAY + "" + i + ". " + ChatColor.WHITE + nameOf(r[0])
                                + ChatColor.GRAY + " - Lv" + r[1] + " (" + r[2] + " XP)");
                        i++;
                    }
                    if (rows.isEmpty()) player.sendMessage(ChatColor.GRAY + "(Nadie todavia)");
                });
            } catch (Exception ex) {
                plugin.getLogger().warning("[SKILLS] top: " + ex.getMessage());
            }
        });
    }

    @SuppressWarnings("deprecation")
    private static String nameOf(String uuidStr) {
        try {
            String n = Bukkit.getOfflinePlayer(UUID.fromString(uuidStr)).getName();
            return n != null ? n : uuidStr.substring(0, 8);
        } catch (Exception e) {
            return uuidStr.substring(0, 8);
        }
    }

    // ─── helpers ─────────────────────────────────────────────
    private void actionBar(Player p, String legacy) { p.sendActionBar(LEGACY.deserialize(legacy)); }
    private void title(Player p, String main, String sub) {
        p.showTitle(Title.title(LEGACY.deserialize(main), LEGACY.deserialize(sub)));
    }
    private static double round2(double v) { return Math.round(v * 100.0) / 100.0; }
    private static String fmt(double v) { return BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP).toPlainString(); }
}
