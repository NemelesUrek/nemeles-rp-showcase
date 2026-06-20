package com.nemeles.jobs;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Trabajos a los que esta unido un jugador y su progreso (cache en memoria). */
public final class PlayerJobs {

    private final Map<String, JobProgress> jobs = new ConcurrentHashMap<>();
    // perks elegidos: skill -> (tier -> 'A'/'B')
    private final Map<String, Map<Integer, Character>> perks = new ConcurrentHashMap<>();

    public boolean has(String job) { return jobs.containsKey(job); }
    public JobProgress get(String job) { return jobs.get(job); }

    /** Devuelve (creando si no existe) el progreso de una habilidad: se materializa al practicarla. */
    public JobProgress computeIfAbsent(String skill) {
        return jobs.computeIfAbsent(skill, k -> {
            JobProgress p = new JobProgress(1, 0, 0, System.currentTimeMillis());
            p.markDirtyNew();
            return p;
        });
    }
    public void put(String job, JobProgress progress) { jobs.put(job, progress); }
    public void remove(String job) { jobs.remove(job); }
    public int count() { return jobs.size(); }
    public Map<String, JobProgress> all() { return jobs; }

    // ─── perks ───────────────────────────────────────────────
    public char perk(String skill, int tier) {
        Map<Integer, Character> m = perks.get(skill);
        Character c = m == null ? null : m.get(tier);
        return c == null ? '\0' : c;
    }
    public void putPerk(String skill, int tier, char c) {
        perks.computeIfAbsent(skill, k -> new ConcurrentHashMap<>()).put(tier, c);
    }
    public Map<Integer, Character> perksOf(String skill) { return perks.getOrDefault(skill, Map.of()); }
}
