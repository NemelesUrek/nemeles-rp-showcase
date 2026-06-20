package com.nemeles.jobs.level;

/** Curva de niveles/XP de los trabajos. Configurable desde config.yml. */
public final class LevelCurve {

    private final int base;
    private final int quad;
    private final int cap;
    private final double payScalePerLevel;

    public LevelCurve(int base, int quad, int cap, double payScalePerLevel) {
        this.base = base;
        this.quad = quad;
        this.cap = cap;
        this.payScalePerLevel = payScalePerLevel;
    }

    public int cap() {
        return cap;
    }

    /** XP necesaria para pasar de {@code level} a {@code level + 1}. */
    public long xpToNext(int level) {
        return (long) base * level + (long) quad * level * level;
    }

    /** Multiplicador de pago segun nivel: 1 + (nivel-1) * escala. */
    public double payMultiplier(int level) {
        return 1.0 + (level - 1) * payScalePerLevel;
    }
}
