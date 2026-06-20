package com.nemeles.territories;

import org.bukkit.configuration.file.FileConfiguration;

import java.util.HashMap;
import java.util.Map;

/** Snapshot inmutable de la config (se lee una vez al arrancar). Dinero en CENTIMOS. */
final class TerrConfig {

    final boolean respectSafezone;
    final int tickSeconds, afkSeconds, warnSeconds, captureSeconds, attackersCap, defendersCap;
    final long shieldMs, captureCostCents;
    final boolean costOnAuto;
    final long warPotCents;
    final int warPrepMinutes, warWindowMinutes;
    final int maxPerFactionDivisor;
    final double zoneDiminishing;
    final int payoutIntervalSeconds;
    final long incomeBaseCents;
    final long incomeMaxCents;   // tope de renta por periodo y zona (0 = sin tope)
    final boolean incomeOnlineGated;
    final Map<Integer, Double> tierMult;
    final double taxWeed, taxWeedAlly, taxWeedEnemy, taxWeedMember;
    final long upkeepBaseCents;
    final double upkeepExponent;
    final int upkeepIntervalHours, decayGraceDays;
    final int influencePerTick;          // lealtad ganada por economyTick (60s) en zona controlada
    final long influenceMaxUnits;        // tope de lealtad (0 = sin influencia)
    final double influenceCaptureBonus;  // a lealtad maxima, la captura tarda (1+bonus)x

    private TerrConfig(boolean respectSafezone, int tickSeconds, int afkSeconds, int warnSeconds,
                       int captureSeconds, int attackersCap, int defendersCap, long shieldMs,
                       long captureCostCents, boolean costOnAuto, long warPotCents, int warPrepMinutes,
                       int warWindowMinutes, int maxPerFactionDivisor,
                       double zoneDiminishing, int payoutIntervalSeconds, long incomeBaseCents,
                       long incomeMaxCents, boolean incomeOnlineGated, Map<Integer, Double> tierMult, double taxWeed,
                       double taxWeedAlly, double taxWeedEnemy, double taxWeedMember, long upkeepBaseCents,
                       double upkeepExponent, int upkeepIntervalHours, int decayGraceDays,
                       int influencePerTick, long influenceMaxUnits, double influenceCaptureBonus) {
        this.respectSafezone = respectSafezone;
        this.tickSeconds = tickSeconds;
        this.afkSeconds = afkSeconds;
        this.warnSeconds = warnSeconds;
        this.captureSeconds = captureSeconds;
        this.attackersCap = attackersCap;
        this.defendersCap = defendersCap;
        this.shieldMs = shieldMs;
        this.captureCostCents = captureCostCents;
        this.costOnAuto = costOnAuto;
        this.warPotCents = warPotCents;
        this.warPrepMinutes = warPrepMinutes;
        this.warWindowMinutes = warWindowMinutes;
        this.maxPerFactionDivisor = maxPerFactionDivisor;
        this.zoneDiminishing = zoneDiminishing;
        this.payoutIntervalSeconds = payoutIntervalSeconds;
        this.incomeBaseCents = incomeBaseCents;
        this.incomeMaxCents = incomeMaxCents;
        this.incomeOnlineGated = incomeOnlineGated;
        this.tierMult = tierMult;
        this.taxWeed = taxWeed;
        this.taxWeedAlly = taxWeedAlly;
        this.taxWeedEnemy = taxWeedEnemy;
        this.taxWeedMember = taxWeedMember;
        this.upkeepBaseCents = upkeepBaseCents;
        this.upkeepExponent = upkeepExponent;
        this.upkeepIntervalHours = upkeepIntervalHours;
        this.decayGraceDays = decayGraceDays;
        this.influencePerTick = influencePerTick;
        this.influenceMaxUnits = influenceMaxUnits;
        this.influenceCaptureBonus = influenceCaptureBonus;
    }

    static TerrConfig from(FileConfiguration c) {
        Map<Integer, Double> tm = new HashMap<>();
        tm.put(1, c.getDouble("income.tier-multipliers.1", 1.0));
        tm.put(2, c.getDouble("income.tier-multipliers.2", 1.6));
        tm.put(3, c.getDouble("income.tier-multipliers.3", 2.4));
        return new TerrConfig(
            c.getBoolean("respect-safezone", true),
            Math.max(1, c.getInt("contest.tick-seconds", 5)),
            c.getInt("contest.afk-seconds", 60),
            c.getInt("contest.warn-seconds", 60),
            Math.max(1, c.getInt("contest.capture-seconds", 300)),
            Math.max(1, c.getInt("contest.attackers-cap", 5)),
            Math.max(1, c.getInt("contest.defenders-cap", 8)),
            (long) c.getInt("contest.shield-minutes", 60) * 60_000L,
            Math.round(c.getDouble("capture.cost", 5000) * 100.0),
            c.getBoolean("capture.cost-on-auto", false),
            Math.max(0, Math.round(c.getDouble("war.pot", 50000) * 100.0)),
            Math.max(0, c.getInt("war.prep-minutes", 10)),
            Math.max(1, c.getInt("war.window-minutes", 30)),
            Math.max(1, c.getInt("limits.max-per-faction-divisor", 4)),
            c.getDouble("limits.zone-diminishing", 0.85),
            Math.max(60, c.getInt("income.payout-interval-seconds", 600)),
            Math.round(c.getDouble("income.base", 120) * 100.0),
            Math.max(0, Math.round(c.getDouble("income.max-per-period", 0) * 100.0)),
            c.getBoolean("income.online-gated", true),
            tm,
            c.getDouble("tax.weed", 0.15),
            c.getDouble("tax.weed-ally", 0.07),
            c.getDouble("tax.weed-enemy", 0.25),
            c.getDouble("tax.weed-member", 0.0),
            Math.round(c.getDouble("upkeep.base", 1000) * 100.0),
            c.getDouble("upkeep.exponent", 1.4),
            Math.max(1, c.getInt("upkeep.interval-hours", 24)),
            Math.max(0, c.getInt("upkeep.decay-grace-days", 3)),
            Math.max(0, c.getInt("influence.per-tick", 1)),
            Math.max(0L, Math.round(c.getDouble("influence.max-units", 1440))),
            Math.max(0.0, c.getDouble("influence.capture-bonus", 1.0))
        );
    }

    double tierMult(int tier) { return tierMult.getOrDefault(tier, 1.0); }
}
