package com.nemeles.core.api.property;

import org.bukkit.Location;

import java.util.Optional;
import java.util.UUID;

/**
 * Sistema de propiedades (lo registra nemeles-properties). Otros modulos lo consultan en try/catch:
 * acceso a cofres/puertas y punto de respawn. Lecturas sincronas desde cache.
 */
public interface PropertyService {

    /** Propiedad en esa ubicacion (vacio si ninguna). */
    Optional<PropertySnapshot> propertyAt(Location loc);

    /** ¿Puede el jugador abrir cofres/puertas en esa ubicacion? (true si no es propiedad). */
    boolean canAccess(UUID player, Location loc);

    /** Punto de respawn del jugador (su casa o la base de su mafia), si tiene. */
    Optional<Location> respawnFor(UUID player);
}
