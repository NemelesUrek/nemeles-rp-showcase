package com.nemeles.core.api.police;

import java.util.UUID;

/** Vista inmutable del estado de búsqueda (wanted) de un jugador. */
public record WantedSnapshot(UUID player, int points, int stars,
                             long lastCrimeAt, String lastCrimeId, String lastRegion) {

    /** Estrellas (0..5) derivadas de los puntos (0..100). */
    public static int starsForPoints(int points) {
        if (points <= 0) return 0;
        if (points <= 20) return 1;
        if (points <= 40) return 2;
        if (points <= 60) return 3;
        if (points <= 80) return 4;
        return 5;
    }
}
