package com.nemeles.core.api.vehicle;

import org.bukkit.entity.Entity;

import java.util.Optional;
import java.util.UUID;

/**
 * Sistema de vehiculos (lo registra nemeles-vehicles). Otros modulos lo consultan en try/catch:
 * p.ej. el combate ignora las entidades-vehiculo, o la policia comprueba si alguien conduce.
 */
public interface VehicleService {

    /** true si la entidad es un vehiculo gestionado por el plugin (PDC). */
    boolean isVehicleEntity(Entity e);

    /** true si el jugador esta conduciendo ahora mismo. */
    boolean isDriving(UUID player);

    /** Id del vehiculo que conduce el jugador (vacio si ninguno). */
    Optional<UUID> vehicleDrivenBy(UUID player);

    /**
     * Aplica dano de un disparo (HITSCAN) a la pieza del vehiculo segun el punto de impacto.
     * Lo llama el modulo de combate cuando una bala impacta una entidad-vehiculo. No-op si la
     * entidad no es un vehiculo activo. shooter puede ser null.
     */
    void damageFromShot(Entity seat, org.bukkit.Location impact, org.bukkit.entity.Player shooter);

    /**
     * Repara al 100% (motor/ruedas/suspension) el vehiculo que CONDUCE ese jugador ahora mismo.
     * NO cobra nada (el dinero lo gestiona quien llama, p.ej. la carrera de mecanico).
     * @return true si habia vehiculo activo y se reparo.
     */
    boolean repairDriving(UUID driver);
}
