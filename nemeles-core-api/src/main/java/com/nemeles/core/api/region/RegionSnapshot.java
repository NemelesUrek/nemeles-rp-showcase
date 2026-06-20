package com.nemeles.core.api.region;

import java.util.Set;

/** Vista inmutable de la region de mayor prioridad en una ubicacion (wrapper sobre WorldGuard). */
public final class RegionSnapshot {

    private final String id;
    private final int priority;
    private final Set<String> owners;

    public RegionSnapshot(String id, int priority, Set<String> owners) {
        this.id = id;
        this.priority = priority;
        this.owners = (owners == null) ? Set.of() : Set.copyOf(owners);
    }

    public String id() { return id; }
    public int priority() { return priority; }
    public Set<String> owners() { return owners; }
}
