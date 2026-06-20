package com.nemeles.core.api.police;

import org.bukkit.Location;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Sistema de búsqueda (wanted) de NemelesRP. Lo registra el módulo nemeles-police y lo consumen otros
 * módulos (la marihuana de nemeles-jobs, futuros atracos...) sin acoplarse a la implementación.
 *
 * <p>Lecturas síncronas desde caché (para PAPI/scoreboard); escrituras asíncronas (IO en dbExecutor).</p>
 */
public interface WantedService {

    /**
     * Suma puntos de búsqueda por un crimen. Respeta safezone internamente (si la ubicación no permite
     * crimen, no suma nada). Si {@code masked} es false, aplica un x1.5 por dejar evidencia. Refresca la
     * última ubicación conocida.
     */
    CompletableFuture<WantedSnapshot> addCrime(UUID player, int points, String crimeId, Location where, boolean masked);

    int getStars(UUID player);     // 0..5
    int getPoints(UUID player);    // 0..100
    boolean isWanted(UUID player); // stars >= 1
    WantedSnapshot snapshot(UUID player);

    /** Limpia el wanted (arresto, amnistía). */
    CompletableFuture<Void> clear(UUID player, String reason);

    /** Un policía avistó al buscado: refresca la última ubicación conocida y reinicia el reloj de decay. */
    void registerSighting(UUID player, Location where);
}
