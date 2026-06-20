package com.nemeles.jobs.model;

/**
 * Categoria de un trabajo.
 * <ul>
 *   <li>{@link #RECOLECCION} – romper bloques del mundo (mineria, tala).</li>
 *   <li>{@link #AGRICULTURA} – cosechar cultivos MADUROS (paga solo en estado maduro).</li>
 *   <li>{@link #SERVICIO} – misiones/clientes NPC automaticos (futuro).</li>
 *   <li>{@link #ILEGAL} – paga dinero SUCIO (futuro: drogas).</li>
 * </ul>
 */
public enum JobCategory {
    RECOLECCION,
    AGRICULTURA,
    SERVICIO,
    ILEGAL
}
