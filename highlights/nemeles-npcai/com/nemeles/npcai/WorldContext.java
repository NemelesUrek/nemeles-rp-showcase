package com.nemeles.npcai;

import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.UUID;

/**
 * PIEZA 7 — "El NPC lee la ciudad". Construye, compacta y a prueba de fallos, una linea de contexto del
 * mundo para el system prompt: alerta de ciudad, territorio+dueno, wanted, calor y zona. Cada dato en su
 * propio try/catch: si un modulo no esta, ese dato se omite. Solo produce TEXTO (crossplay).
 */
public final class WorldContext {

    private final AiConfig cfg;
    private final boolean papiPresent;

    public WorldContext(AiConfig cfg) {
        this.cfg = cfg;
        boolean papi;
        try { papi = org.bukkit.Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI"); }
        catch (Throwable t) { papi = false; }
        this.papiPresent = papi;
    }

    public String build(Player p, UUID uid, boolean missionGiver) {
        if (!cfg.worldCtxEnabled || p == null) return "";

        Location loc = safeLocation(p);
        String alert      = cfg.worldCtxAlert      ? cityAlert(p)        : null;
        String territory  = cfg.worldCtxTerritory  ? territoryHere(loc)  : null;
        String owner      = cfg.worldCtxTerritory  ? territoryOwner(loc) : null;
        boolean contested = cfg.worldCtxTerritory  && territoryContested(loc);
        int stars         = cfg.worldCtxWanted     ? wantedStars(uid)    : -1;
        int heat          = cfg.worldCtxHeat       ? playerHeat(uid)     : -1;
        boolean safezone  = cfg.worldCtxZone       && isSafezone(loc);
        boolean erial     = cfg.worldCtxZone       && isErial(loc);

        StringBuilder facts = new StringBuilder();
        if (alert != null && !alert.isBlank())
            facts.append(" Hoy la ciudad esta en alerta ").append(alert.toLowerCase(java.util.Locale.ROOT)).append('.');
        if (territory != null && !territory.isBlank()) {
            facts.append(" Ahora mismo estais en ").append(territory);
            if (owner != null && !owner.isBlank()) {
                if (owner.equalsIgnoreCase("nadie")) facts.append(", zona de nadie");
                else facts.append(", territorio de ").append(owner);
            }
            if (contested) facts.append(" y AHORA MISMO se esta peleando (hay una captura en curso)");
            facts.append('.');
        } else if (safezone) {
            facts.append(" Estais en una zona segura de la ciudad.");
        }
        if (erial) facts.append(" Estais en El Erial, la zona de muerte permanente; aqui todos hablan con miedo.");
        if (stars >= 5)      facts.append(" Este ciudadano esta buscadisimo por la policia (5 estrellas).");
        else if (stars >= 3) facts.append(" Este ciudadano esta muy buscado por la policia.");
        else if (stars >= 1) facts.append(" Este ciudadano tiene algun lio con la ley.");
        if (heat >= 70)      facts.append(" Ademas, esta MUY caliente: la ciudad lo vigila de cerca.");
        else if (heat >= 40) facts.append(" Ademas, arrastra algo de calor; conviene andarse con ojo.");

        if (facts.length() == 0) return "";

        StringBuilder out = new StringBuilder();
        out.append("\nCONTEXTO DEL MUNDO AHORA MISMO (uselo con NATURALIDAD solo si encaja, NUNCA lo recites de golpe ni des cifras tecnicas):")
           .append(facts);
        if (missionGiver) out.append(' ').append(cfg.worldCtxMissionHint);
        return out.toString();
    }

    private String cityAlert(Player p) {
        String v = papi(p, "%cityalert_level%");
        if (v == null) return "";
        v = v.trim();
        // VERDE = calma (estado por defecto). No es una "alerta": no lo metemos en el prompt o saldria siempre.
        if (v.isEmpty() || v.equalsIgnoreCase("VERDE")) return "";
        return v;
    }

    private String territoryHere(Location loc) {
        if (loc == null) return "";
        try {
            return com.nemeles.core.api.NemelesApi.territories().territoryAt(loc)
                    .map(com.nemeles.core.api.territory.TerritorySnapshot::name).orElse("");
        } catch (Throwable ignored) { return ""; }
    }

    private String territoryOwner(Location loc) {
        if (loc == null) return "";
        try {
            var svc = com.nemeles.core.api.NemelesApi.territories();
            var snap = svc.territoryAt(loc).orElse(null);
            if (snap == null) return "";
            if (snap.isNeutral()) return "nadie";
            try {
                return com.nemeles.core.api.NemelesApi.factions().getFaction(snap.ownerFactionId())
                        .map(com.nemeles.core.api.faction.Faction::name).orElse("una mafia");
            } catch (Throwable ignored) { return "una mafia"; }
        } catch (Throwable ignored) { return ""; }
    }

    private boolean territoryContested(Location loc) {
        if (loc == null) return false;
        try {
            var svc = com.nemeles.core.api.NemelesApi.territories();
            var snap = svc.territoryAt(loc).orElse(null);
            return snap != null && svc.isContested(snap.id());
        } catch (Throwable ignored) { return false; }
    }

    private int wantedStars(UUID uid) {
        try { return com.nemeles.core.api.NemelesApi.wanted().getStars(uid); }
        catch (Throwable ignored) { return -1; }
    }

    private int playerHeat(UUID uid) {
        try { return (int) Math.round(com.nemeles.core.api.NemelesApi.heat().playerHeat(uid)); }
        catch (Throwable ignored) { return -1; }
    }

    private boolean isSafezone(Location loc) {
        if (loc == null) return false;
        try { return com.nemeles.core.api.NemelesApi.regions().isSafezone(loc); }
        catch (Throwable ignored) { return false; }
    }

    private boolean isErial(Location loc) {
        if (loc == null) return false;
        try { return com.nemeles.core.api.NemelesApi.regions().isPermadeathZone(loc); }
        catch (Throwable ignored) { return false; }
    }

    private static Location safeLocation(Player p) {
        try { return p.getLocation(); } catch (Throwable ignored) { return null; }
    }

    private String papi(Player player, String placeholder) {
        if (!papiPresent || player == null) return "";
        try {
            String out = me.clip.placeholderapi.PlaceholderAPI.setPlaceholders(player, placeholder);
            if (out == null || out.isEmpty() || out.equalsIgnoreCase(placeholder)) return "";
            out = out.replaceAll("[&\u00a7][0-9a-fk-orA-FK-OR]", "").trim();
            return out;
        } catch (Throwable ex) { return ""; }
    }
}
