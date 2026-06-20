package com.nemeles.jobs.perk;

import java.util.Map;

/** Una opción de perk (A o B) de un hito. Sus efectos los leen los módulos via SkillService.perkValue. */
public final class PerkChoice {

    public final String skillId;
    public final int tier;
    public final char option;       // 'A' | 'B'
    public final String id;         // skill.tier.a / skill.tier.b
    public final String displayName;
    public final Map<String, Double> effects;

    public PerkChoice(String skillId, int tier, char option, String id, String displayName, Map<String, Double> effects) {
        this.skillId = skillId;
        this.tier = tier;
        this.option = option;
        this.id = id;
        this.displayName = displayName;
        this.effects = effects;
    }

    public double effect(String key) {
        Double d = effects.get(key);
        return d == null ? 0.0 : d;
    }
}
