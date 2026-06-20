package com.nemeles.core.api.economy;

/**
 * Los cuatro saldos de la economia dual de NemelesRP.
 *
 * <ul>
 *   <li>{@link #EFECTIVO} – dinero fisico encima del jugador; se puede robar/perder al morir.</li>
 *   <li>{@link #BANCO}    – saldo seguro, no se pierde al morir.</li>
 *   <li>{@link #SUCIO}    – dinero ilegal sin lavar; no se puede usar en tiendas legales.</li>
 *   <li>{@link #LIMPIO}   – dinero legal/lavado; uso libre.</li>
 * </ul>
 */
public enum MoneyType {
    EFECTIVO,
    BANCO,
    SUCIO,
    LIMPIO
}
