package com.nemeles.core.api.territory;

/** Vista inmutable de un territorio (espejo de WantedSnapshot). */
public record TerritorySnapshot(int id, String name, String world, int tier,
                                int ownerFactionId, long incomeCents, String state,
                                int chunkCount, long capturedAt, long shieldUntil) {

    public boolean isNeutral() { return ownerFactionId < 0; }

    public boolean isShielded(long now) { return now < shieldUntil; }
}
