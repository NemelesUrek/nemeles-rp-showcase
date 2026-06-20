package com.nemeles.jobs.employment;

import com.nemeles.jobs.JobRegistry;
import com.nemeles.jobs.model.JobDefinition;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

/**
 * Capa de EMPLEO (identidad económica): un trabajo por jugador. Solo con tu empleo ganas dinero por esa
 * actividad y subes XP a ritmo normal; sin empleo (o en otra actividad) puedes hacerlo pero sin pago y
 * con XP reducida. Cambiar/dejar el empleo aplica un cooldown (penalización → fomenta especialización).
 */
public final class EmploymentManager {

    private final Plugin plugin;
    private final EmploymentDao dao;
    private final Executor dbExecutor;
    private final JobRegistry registry;
    private final long cooldownMs;
    private final double noJobXpFactor;
    private final double offJobXpFactor;
    private final Map<UUID, EmploymentRecord> cache = new ConcurrentHashMap<>();

    public EmploymentManager(Plugin plugin, EmploymentDao dao, Executor dbExecutor, JobRegistry registry,
                             long cooldownMs, double noJobXpFactor, double offJobXpFactor) {
        this.plugin = plugin;
        this.dao = dao;
        this.dbExecutor = dbExecutor;
        this.registry = registry;
        this.cooldownMs = cooldownMs;
        this.noJobXpFactor = noJobXpFactor;
        this.offJobXpFactor = offJobXpFactor;
    }

    public void load(UUID uuid) {
        dbExecutor.execute(() -> {
            try {
                EmploymentRecord r = dao.load(uuid);
                cache.put(uuid, r != null ? r : new EmploymentRecord(null, 0));
            } catch (Exception e) {
                plugin.getLogger().warning("[EMPLEO] Error cargando " + uuid + ": " + e.getMessage());
                cache.put(uuid, new EmploymentRecord(null, 0));
            }
        });
    }

    public void evict(UUID uuid) { cache.remove(uuid); }

    public String getJob(UUID uuid) {
        EmploymentRecord r = cache.get(uuid);
        return r == null ? null : r.job;
    }

    /** Multiplicador de XP: 1.0 si la actividad es tu empleo; reducido si no tienes empleo o es otra. */
    public double xpFactor(UUID uuid, String skill) {
        String job = getJob(uuid);
        if (job != null && job.equals(skill)) return 1.0;
        return job == null ? noJobXpFactor : offJobXpFactor;
    }

    /** Solo cobras dinero por una actividad si es la de tu empleo. */
    public boolean paysMoneyForSkill(UUID uuid, String skill) {
        String job = getJob(uuid);
        return job != null && job.equals(skill);
    }

    public void join(Player p, String jobId) {
        JobDefinition def = registry.get(jobId);
        if (def == null) {
            p.sendMessage(ChatColor.RED + "[Empleo] Ese trabajo no existe. Usa /empleo list.");
            return;
        }
        EmploymentRecord r = cache.get(p.getUniqueId());
        if (r == null) {
            p.sendMessage(ChatColor.RED + "[Empleo] Cargando tus datos, intenta de nuevo.");
            return;
        }
        if (jobId.equals(r.job)) {
            p.sendMessage(ChatColor.YELLOW + "[Empleo] Ya trabajas como " + def.displayName() + ".");
            return;
        }
        long now = System.currentTimeMillis();
        long rem = cooldownRemaining(r, now);
        if (r.lastChange > 0 && rem > 0) {
            p.sendMessage(ChatColor.RED + "[Empleo] Debes esperar " + fmtDur(rem) + " para cambiar de trabajo.");
            return;
        }
        r.job = jobId;
        r.lastChange = now;
        persist(p.getUniqueId(), r);
        p.sendMessage(ChatColor.GREEN + "[Empleo] Ahora trabajas como " + ChatColor.WHITE + def.displayName()
                + ChatColor.GREEN + ". Ganas dinero y XP completa en esta actividad.");
    }

    public void leave(Player p) {
        EmploymentRecord r = cache.get(p.getUniqueId());
        if (r == null || r.job == null) {
            p.sendMessage(ChatColor.YELLOW + "[Empleo] No tienes empleo.");
            return;
        }
        r.job = null;
        r.lastChange = System.currentTimeMillis();
        persist(p.getUniqueId(), r);
        p.sendMessage(ChatColor.YELLOW + "[Empleo] Dejaste tu empleo. No podrás tomar otro en "
                + fmtDur(cooldownMs) + " (penalización por renunciar).");
    }

    public void info(Player p) {
        EmploymentRecord r = cache.get(p.getUniqueId());
        String job = r == null ? null : r.job;
        if (job == null) {
            p.sendMessage(ChatColor.GRAY + "[Empleo] Estás " + ChatColor.WHITE + "DESEMPLEADO"
                    + ChatColor.GRAY + ": puedes hacer actividades pero sin pago y con XP lenta. Usa /empleo list.");
        } else {
            JobDefinition def = registry.get(job);
            p.sendMessage(ChatColor.GREEN + "[Empleo] Trabajas como " + ChatColor.WHITE
                    + (def != null ? def.displayName() : job) + ChatColor.GREEN + ".");
        }
        if (r != null && r.lastChange > 0) {
            long rem = cooldownRemaining(r, System.currentTimeMillis());
            if (rem > 0) p.sendMessage(ChatColor.GRAY + "Cooldown para cambiar: " + fmtDur(rem));
        }
    }

    public void list(Player p) {
        p.sendMessage(ChatColor.AQUA + "== Empleos disponibles ==");
        String cur = getJob(p.getUniqueId());
        for (JobDefinition def : registry.all()) {
            String mark = def.id().equals(cur) ? ChatColor.GREEN + " (actual)" : "";
            p.sendMessage(ChatColor.GRAY + "- " + ChatColor.WHITE + def.displayName()
                    + ChatColor.DARK_GRAY + " (" + def.id() + ")" + mark);
        }
        p.sendMessage(ChatColor.DARK_GRAY + "Únete con /empleo join <id>. Solo con tu empleo ganas dinero por esa actividad.");
    }

    private long cooldownRemaining(EmploymentRecord r, long now) {
        return Math.max(0, cooldownMs - (now - r.lastChange));
    }

    private void persist(UUID uuid, EmploymentRecord r) {
        dbExecutor.execute(() -> {
            try { dao.upsert(uuid, r); }
            catch (Exception e) { plugin.getLogger().warning("[EMPLEO] save: " + e.getMessage()); }
        });
    }

    private static String fmtDur(long ms) {
        long m = ms / 60000;
        if (m < 60) return m + " min";
        long h = m / 60;
        return h + " h " + (m % 60) + " min";
    }
}
