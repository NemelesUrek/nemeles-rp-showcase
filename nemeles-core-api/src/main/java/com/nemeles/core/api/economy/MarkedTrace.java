package com.nemeles.core.api.economy;

import java.math.BigDecimal;

/**
 * Vista inmutable de un lote de dinero LIMPIO "marcado" (rastreable) producto de un lavado. Es la unidad
 * que la policia consulta para "seguir el dinero": de donde salio ({@code channel}), en que {@code zona}
 * se lavo, cuanto y cuando, y si ya paso por el cajero ({@code cleared}) y en que momento.
 *
 * <ul>
 *   <li>{@code code}       – id del lote (ej. "MK-1234").</li>
 *   <li>{@code amountUnits}– importe del lote en UNIDADES (no centimos), escala 2.</li>
 *   <li>{@code channel}    – origen del lavado (ej. "business:ID" / "fachada:ID"); puede ser null en datos viejos.</li>
 *   <li>{@code zona}       – region donde se lavo (RegionService); null si no se pudo resolver.</li>
 *   <li>{@code createdAt}  – epoch millis del lavado.</li>
 *   <li>{@code cleared}    – true si el lote ya se limpio en el cajero.</li>
 *   <li>{@code clearedAt}  – epoch millis del cajeo, o 0 si sigue sin limpiar.</li>
 * </ul>
 */
public record MarkedTrace(String code, BigDecimal amountUnits, String channel, String zona,
                          long createdAt, boolean cleared, long clearedAt) {
}
