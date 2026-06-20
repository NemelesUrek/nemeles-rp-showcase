package com.nemeles.territories;

import com.nemeles.core.api.territory.TerritorySnapshot;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Territorio en memoria (mutable). Mapea a {@link TerritorySnapshot} para la API. */
public final class TerritoryData {

    public static final String ACTIVE = "ACTIVE";
    public static final String DECAY = "DECAY";

    public final int id;
    public String name;
    public final String world;
    public int tier;
    public int ownerFaction;          // -1 = neutral
    public long incomeCents;
    public String state;              // ACTIVE | DECAY
    public long capturedAt;
    public long shieldUntil;
    public long lastIncomeAt;
    public long lastUpkeepAt;
    public long influence;            // lealtad acumulada por tiempo controlado (sube la dificultad de captura)
    public final Set<Long> chunks = ConcurrentHashMap.newKeySet();

    public TerritoryData(int id, String name, String world, int tier, int ownerFaction, long incomeCents,
                         String state, long capturedAt, long shieldUntil, long lastIncomeAt, long lastUpkeepAt) {
        this.id = id;
        this.name = name;
        this.world = world;
        this.tier = tier;
        this.ownerFaction = ownerFaction;
        this.incomeCents = incomeCents;
        this.state = state;
        this.capturedAt = capturedAt;
        this.shieldUntil = shieldUntil;
        this.lastIncomeAt = lastIncomeAt;
        this.lastUpkeepAt = lastUpkeepAt;
    }

    public boolean isShielded(long now) { return now < shieldUntil; }

    public TerritorySnapshot snapshot() {
        return new TerritorySnapshot(id, name, world, tier, ownerFaction, incomeCents, state,
                chunks.size(), capturedAt, shieldUntil);
    }
}
