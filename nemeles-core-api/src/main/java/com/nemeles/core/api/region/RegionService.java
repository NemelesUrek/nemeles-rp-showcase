package com.nemeles.core.api.region;

import org.bukkit.Location;
import org.bukkit.World;

import java.util.Set;

/**
 * Wrapper de alto nivel sobre WorldGuard para el resto de modulos. Aisla la dependencia de WorldGuard
 * en el core: los modulos preguntan en terminos de juego (safezone, crimen, zona negra) sin tocar su API.
 */
public interface RegionService {

    /** Zona segura: sin PvP ni construccion (flag global de proteccion). */
    boolean isSafezone(Location loc);

    /** Si en esta ubicacion se permite cometer crimenes (flag custom). */
    boolean canCommitCrime(Location loc);

    /** "Zona Negra": area de alto riesgo con muerte permanente (flag custom nemeles-permadeath). */
    boolean isPermadeathZone(Location loc);

    /** Region de mayor prioridad en la ubicacion, o null si no hay ninguna. */
    RegionSnapshot getRegionAt(Location loc);

    /** Ids de todas las regiones que cubren la ubicacion. */
    Set<String> regionIdsAt(Location loc);

    /** ¿Existe una region con ese id en el mundo? (para el modulo de propiedades). */
    boolean regionExists(World world, String regionId);

    /** ¿La region contiene la ubicacion? false si la region no existe. */
    boolean regionContains(String regionId, Location loc);

    /** Claves de chunk que toca el cuboide de la region (para indices espaciales O(1)). */
    Set<Long> regionChunkKeys(World world, String regionId);
}
