package com.nemeles.core.api.identity;

import java.util.UUID;

/**
 * Puente cross-plugin para la IDENTIDAD OCULTA del servidor. Lo implementa nemeles-identity y lo publica
 * en {@link com.nemeles.core.api.NemelesApi}; cualquier otro plugin obtiene el alias anónimo estable o el
 * nombre RP de un jugador SIN depender directamente de nemeles-identity (que mantiene su lógica interna).
 *
 * <p>Pensado para que módulos como el MERCADO NEGRO no filtren nunca el nombre de cuenta de Minecraft a
 * Discord. Todos los métodos son SÍNCRONOS y seguros de llamar en el hilo principal (lectura de caché en
 * memoria) y nunca lanzan excepción (devuelven cadena vacía / false ante cualquier fallo).</p>
 */
public interface IdentityService {

    /** Alias anónimo ESTABLE por-UUID que el servidor muestra cuando no te conocen, p.ej. "Desconocido #A4F2". */
    String anonAlias(UUID id);

    /** Handle corto y estable derivado del UUID, p.ej. "#A4F2" (sin el prefijo de "Desconocido"). */
    String handle(UUID id);

    /** Nombre del personaje del DNI/rol, o cadena vacía si no hay DNI completo. */
    String rpName(UUID id);

    /** ¿El jugador tiene un DNI de rol completo? */
    boolean hasDni(UUID id);
}
