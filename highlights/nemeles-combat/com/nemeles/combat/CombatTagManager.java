package com.nemeles.combat;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Combat-tag (logout = muerte) y registro de agresor (defensa propia). TTL en memoria. */
public final class CombatTagManager {

    private final long tagMs;
    private final Map<UUID, Long> combatUntil = new ConcurrentHashMap<>();
    private final Map<String, Long> aggro = new ConcurrentHashMap<>();

    public CombatTagManager(int tagSeconds) {
        this.tagMs = tagSeconds * 1000L;
    }

    public void tag(UUID attacker, UUID victim) {
        long until = System.currentTimeMillis() + tagMs;
        if (attacker != null) combatUntil.put(attacker, until);
        if (victim != null) combatUntil.put(victim, until);
        if (attacker != null && victim != null) aggro.put(attacker + ":" + victim, until);
    }

    public boolean inCombat(UUID u) {
        Long t = combatUntil.get(u);
        return t != null && t > System.currentTimeMillis();
    }

    /** ¿{@code attacker} agredio a {@code victim} dentro de la ventana? (para defensa propia). */
    public boolean wasAggressor(UUID attacker, UUID victim) {
        Long t = aggro.get(attacker + ":" + victim);
        return t != null && t > System.currentTimeMillis();
    }

    public void clear(UUID u) {
        combatUntil.remove(u);
    }
}
