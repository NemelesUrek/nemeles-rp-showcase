package com.nemeles.core.api.career;

import java.util.Optional;
import java.util.UUID;

/**
 * Carreras/empleos de servicio (policia, medico, mecanico, taxista). Lo registra nemeles-careers.
 * Otros modulos (telefono, policia...) lo consultan en try/catch.
 */
public interface CareerService {

    /** Carrera actual del jugador (id en minusculas: "police","medic","mechanic","taxi"), vacio si civil. */
    Optional<String> careerOf(UUID player);

    /** true si el jugador esta DE SERVICIO ahora mismo. */
    boolean isOnDuty(UUID player);

    /** Nivel de carrera (1..max) del jugador en su carrera actual (0 si no tiene). */
    int levelOf(UUID player);

    /** Cuantos jugadores estan DE SERVICIO en esa carrera ahora mismo (p.ej. policias online). */
    int onDutyCount(String careerId);
}
