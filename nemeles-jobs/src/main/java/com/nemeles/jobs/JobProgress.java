package com.nemeles.jobs;

/**
 * Progreso de un jugador en UNA habilidad: nivel, XP del nivel actual, XP total (ranking),
 * ultima vez usada (para la decadencia) y nivel maximo alcanzado (suelo que protege perks).
 */
public final class JobProgress {

    private int level;
    private long xp;
    private long totalXp;
    private long lastUsed;
    private int peakLevel;
    private boolean dirty;

    public JobProgress(int level, long xp, long totalXp, long lastUsed) {
        this(level, xp, totalXp, lastUsed, level);
    }

    public JobProgress(int level, long xp, long totalXp, long lastUsed, int peakLevel) {
        this.level = level;
        this.xp = xp;
        this.totalXp = totalXp;
        this.lastUsed = lastUsed;
        this.peakLevel = peakLevel;
    }

    public int level() { return level; }
    public long xp() { return xp; }
    public long totalXp() { return totalXp; }
    public long lastUsed() { return lastUsed; }
    public int peakLevel() { return peakLevel; }
    public boolean dirty() { return dirty; }
    public void clean() { dirty = false; }

    // mutadores
    public void setLevel(int level) { this.level = level; this.dirty = true; }
    public void setXp(long xp) { this.xp = xp; this.dirty = true; }
    public void addXpAndTotal(long amount) { this.xp += amount; this.totalXp += amount; this.dirty = true; }
    public void touch(long now) { this.lastUsed = now; this.dirty = true; }
    public void setPeakLevel(int peak) { this.peakLevel = peak; this.dirty = true; }
    public void markDirtyNew() { this.dirty = true; }
}
