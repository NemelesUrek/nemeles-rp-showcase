package com.nemeles.core.economy;

import java.math.BigDecimal;

/**
 * Resultado de un intento de pago de interes de ahorros (saldo BANCO).
 * <ul>
 *   <li>{@code PAID} — se pago interes ({@link #amount} = lo cobrado, {@link #newBanco} = saldo tras pagar).</li>
 *   <li>{@code NOT_DUE} — aun no toca; {@link #nextEligibleAt} = epoch ms del proximo pago posible.</li>
 *   <li>{@code BELOW_MIN} — toca, pero el saldo no llega al minimo para generar interes.</li>
 *   <li>{@code CLOCK_STARTED} — primera vez que se ve al jugador: se arranco el reloj sin pagar.</li>
 * </ul>
 */
public record InterestResult(Status status, BigDecimal amount, BigDecimal newBanco, long nextEligibleAt) {

    public enum Status { PAID, NOT_DUE, BELOW_MIN, CLOCK_STARTED }

    public boolean wasPaid() { return status == Status.PAID; }

    public static InterestResult paid(BigDecimal amount, BigDecimal newBanco, long nextEligibleAt) {
        return new InterestResult(Status.PAID, amount, newBanco, nextEligibleAt);
    }

    public static InterestResult notDue(long nextEligibleAt) {
        return new InterestResult(Status.NOT_DUE, BigDecimal.ZERO, null, nextEligibleAt);
    }

    public static InterestResult belowMin() {
        return new InterestResult(Status.BELOW_MIN, BigDecimal.ZERO, null, 0L);
    }

    public static InterestResult clockStarted(long nextEligibleAt) {
        return new InterestResult(Status.CLOCK_STARTED, BigDecimal.ZERO, null, nextEligibleAt);
    }
}
