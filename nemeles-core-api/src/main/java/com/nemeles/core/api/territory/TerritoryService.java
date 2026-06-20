package com.nemeles.core.api.territory;

import org.bukkit.Location;
import org.bukkit.World;

import java.util.Collection;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Sistema de territorios / turf wars. Lo registra el modulo nemeles-territories y lo consumen otros
 * modulos (la marihuana llama a {@link #applyDrugTax}) sin acoplarse. Lecturas sincronas desde cache
 * (lookup O(1) por chunk); las escrituras mutan la cache y persisten de forma asincrona.
 */
public interface TerritoryService {

    // ── lecturas (cache, O(1)) ──
    Optional<TerritorySnapshot> territoryAt(Location loc);     // vacio fuera de territorio
    Optional<TerritorySnapshot> getTerritory(int territoryId);
    Optional<TerritorySnapshot> getTerritoryByName(String name);
    int ownerFactionAt(Location loc);                          // -1 si neutral / fuera de zona
    Collection<TerritorySnapshot> all();
    Collection<TerritorySnapshot> ownedBy(int factionId);
    int countOwnedBy(int factionId);
    boolean isContested(int territoryId);                     // hay captura activa
    boolean isShielded(int territoryId);                      // dentro del escudo post-captura

    // ── hook economico (lo llama WeedManager en try/catch) ──
    /**
     * Aplica el peaje de territorio sobre una venta de droga ocurrida en {@code loc}. Si la zona tiene
     * dueno y el vendedor no es de esa faccion, desvia el % configurado del SUCIO a la cuenta de la
     * faccion duena. Devuelve el SUCIO que se queda el vendedor (bruto - peaje); si no aplica, devuelve
     * el bruto intacto. NUNCA lanza.
     */
    long applyDrugTax(UUID seller, Location loc, long grossDirtyCents);

    // ── escrituras (mutan cache + persisten async) ──
    TerritoryResult define(String name, World world, int tier, Set<Long> chunkKeys); // id en codigo
    TerritoryResult dissolve(int territoryId);
    TerritoryResult addChunks(int territoryId, World world, Set<Long> chunkKeys);
    TerritoryResult setOwner(int territoryId, int factionId, String reason);          // -1 = neutral
    TerritoryResult setIncome(int territoryId, long incomeCents);
    TerritoryResult setTier(int territoryId, int tier);

    // ── util espacial (mismo empaquetado que el interno) ──
    static long chunkKey(int chunkX, int chunkZ) {
        return (chunkX & 0xFFFFFFFFL) | ((long) chunkZ << 32);
    }

    static long chunkKeyOf(Location loc) {
        return chunkKey(loc.getBlockX() >> 4, loc.getBlockZ() >> 4);
    }
}
