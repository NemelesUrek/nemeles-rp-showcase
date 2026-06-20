package com.nemeles.jobs.perk;

import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

/** Catálogo de perks (en código). 1 de 2 cada 10 niveles por habilidad. Magnitudes incrementales. */
public final class PerkRegistry {

    private final Map<String, Map<Integer, PerkDef>> tree = new HashMap<>();
    private final Map<String, PerkChoice> byId = new HashMap<>();

    public PerkRegistry() {
        buildMiner();
        buildFarmer();
        buildMedic();
    }

    private void def(PerkChoice a, PerkChoice b) {
        PerkDef d = new PerkDef(a, b);
        tree.computeIfAbsent(a.skillId, k -> new TreeMap<>()).put(a.tier, d);
        byId.put(a.id, a);
        byId.put(b.id, b);
    }

    private static PerkChoice c(String skill, int tier, char opt, String name, Object... kv) {
        Map<String, Double> e = new HashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) e.put((String) kv[i], ((Number) kv[i + 1]).doubleValue());
        String id = skill + "." + tier + "." + (opt == 'A' ? "a" : "b");
        return new PerkChoice(skill, tier, opt, id, name, e);
    }

    public PerkDef get(String skill, int tier) {
        Map<Integer, PerkDef> m = tree.get(skill);
        return m == null ? null : m.get(tier);
    }

    public boolean tierDefined(String skill, int tier) { return get(skill, tier) != null; }
    public Map<Integer, PerkDef> tiers(String skill) { return tree.getOrDefault(skill, Map.of()); }
    public PerkChoice choice(String perkId) { return byId.get(perkId); }

    // ─── MINERÍA ─────────────────────────────────────────────
    private void buildMiner() {
        def(c("miner", 10, 'A', "Veta Generosa I", "mining.double_drop_chance", 0.10),
            c("miner", 10, 'B', "Buen Ojo (menas brillan)"));
        def(c("miner", 20, 'A', "Pulso Firme"),
            c("miner", 20, 'B', "Pies de Plomo"));
        def(c("miner", 30, 'A', "Veta Generosa II", "mining.double_drop_chance", 0.10),
            c("miner", 30, 'B', "Lámpara Interior"));
        def(c("miner", 40, 'A', "Fundición Mental"),
            c("miner", 40, 'B', "Excavador"));
        def(c("miner", 50, 'A', "Fortuna Natural", "mining.bonus_rare", 0.25),
            c("miner", 50, 'B', "Aliento Largo"));
    }

    // ─── AGRICULTURA / MARIHUANA ─────────────────────────────
    private void buildFarmer() {
        def(c("farmer", 10, 'A', "Mano Verde I", "farm.legal_extra", 0.10),
            c("farmer", 10, 'B', "Compostador"));
        def(c("farmer", 20, 'A', "Riego Eficiente"),
            c("farmer", 20, 'B', "Ojo de Botánico"));
        def(c("farmer", 30, 'A', "Mano Verde II", "farm.legal_extra", 0.10),
            c("farmer", 30, 'B', "Disfraz de Huerto"));
        def(c("farmer", 40, 'A', "Cosecha Premium", "weed.bonus_buds", 0.33),
            c("farmer", 40, 'B', "Cultivo Camuflado I", "weed.detect_mult", -0.20));
        def(c("farmer", 50, 'A', "Selección de Semilla"),
            c("farmer", 50, 'B', "Cultivo Camuflado II", "weed.detect_radius_mult", -0.30));
    }

    // ─── MEDICINA ────────────────────────────────────────────
    private void buildMedic() {
        def(c("medic", 10, 'A', "Manos Rápidas I", "revive.channel_bonus", 1.5),
            c("medic", 10, 'B', "Primeros Auxilios", "revive.heal_bonus", 4));
        def(c("medic", 20, 'A', "Vendaje"),
            c("medic", 20, 'B', "Constitución"));
        def(c("medic", 30, 'A', "Manos Rápidas II", "revive.channel_bonus", 1.0),
            c("medic", 30, 'B', "Botiquín Reforzado"));
        def(c("medic", 40, 'A', "Improvisar", "revive.no_kit_chance", 0.20),
            c("medic", 40, 'B', "Inmunidad", "revive.immunity_bonus", 3));
        def(c("medic", 50, 'A', "Soporte de Combate"),
            c("medic", 50, 'B', "Segundo Aliento"));
    }
}
