package com.nemeles.core.api.faction;

import java.util.Set;
import java.util.UUID;

/** Vista de lectura de una facción/mafia. */
public interface Faction {

    int id();
    String name();
    String tag();
    UUID leader();
    boolean open();
    int maxMembers();
    Set<UUID> members();

    /** Prioridad de rango del jugador: 0=líder, 1=oficial, 2=miembro, 3=recluta; -1 si no es miembro. */
    int rankOf(UUID player);
}
