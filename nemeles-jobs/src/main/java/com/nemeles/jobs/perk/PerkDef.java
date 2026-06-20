package com.nemeles.jobs.perk;

/** Un hito de perk: dos opciones excluyentes (A/B) en un nivel de una habilidad. */
public final class PerkDef {

    public final String skillId;
    public final int tier;
    public final PerkChoice a;
    public final PerkChoice b;

    public PerkDef(PerkChoice a, PerkChoice b) {
        this.skillId = a.skillId;
        this.tier = a.tier;
        this.a = a;
        this.b = b;
    }

    public PerkChoice byOption(char o) {
        return o == 'A' ? a : (o == 'B' ? b : null);
    }
}
