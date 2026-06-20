package com.nemeles.jobs.decay;

import com.nemeles.jobs.JobProgress;
import com.nemeles.jobs.level.LevelCurve;

/**
 * Decadencia de habilidad por inactividad. Reglas de oro: gracia generosa, nunca bajar del ultimo
 * multiplo de 10 alcanzado (protege perks), suelo global, y castigo capado por sesion.
 */
public final class DecayService {

    private static final long DAY_MS = 86_400_000L;

    private final LevelCurve curve;
    private final boolean enabled;
    private final long graceDays;
    private final double ratePerDay;
    private final long capDays;
    private final int floorLevel;
    private final double illegalMult;
    private final int maxDropPerLogin;

    public DecayService(LevelCurve curve, boolean enabled, long graceDays, double ratePerDay,
                        long capDays, int floorLevel, double illegalMult, int maxDropPerLogin) {
        this.curve = curve;
        this.enabled = enabled;
        this.graceDays = graceDays;
        this.ratePerDay = ratePerDay;
        this.capDays = capDays;
        this.floorLevel = floorLevel;
        this.illegalMult = illegalMult;
        this.maxDropPerLogin = maxDropPerLogin;
    }

    public boolean enabled() { return enabled; }

    /**
     * Aplica decadencia a una habilidad. Devuelve cuantos NIVELES se perdieron (0 si nada).
     * Mutila {@code prog} (nivel/xp) y actualiza lastUsed para no re-aplicar el mismo periodo.
     */
    public int apply(JobProgress prog, long now, boolean illegal) {
        if (!enabled) return 0;
        long last = prog.lastUsed();
        if (last <= 0) return 0;
        long days = (now - last) / DAY_MS;
        if (days <= graceDays) return 0;

        long effDays = Math.min(days - graceDays, capDays);
        if (effDays <= 0) return 0;

        double mult = illegal ? illegalMult : 1.0;
        long xpLoss = Math.round(effDays * ratePerDay * prog.totalXp() * mult);
        if (xpLoss <= 0) return 0;

        // Suelos: nunca por debajo del ultimo multiplo de 10 alcanzado, ni del suelo global.
        int floorMilestone = (prog.peakLevel() / 10) * 10;
        int floor = Math.max(floorLevel, Math.max(1, floorMilestone));

        int startLevel = prog.level();
        if (startLevel <= floor) {
            prog.touch(now);
            return 0;
        }

        int level = prog.level();
        long xp = prog.xp();
        long loss = xpLoss;
        int drops = 0;
        while (loss > 0 && level > floor && drops < maxDropPerLogin) {
            if (xp >= loss) { xp -= loss; loss = 0; break; }
            loss -= xp;
            level--;
            drops++;
            xp = curve.xpToNext(level); // bajamos un nivel: el resto de la perdida come de su barra
        }
        if (level < floor) { level = floor; xp = 0; }
        if (xp < 0) xp = 0;

        prog.setLevel(level);
        prog.setXp(xp);
        prog.touch(now);
        return startLevel - level;
    }
}
