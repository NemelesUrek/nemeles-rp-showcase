package com.nemeles.core.economy;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Conversion entre {@link BigDecimal} (logica) y centimos como long (persistencia). Nunca usar double. */
public final class Money {

    private Money() {}

    /** BigDecimal -> centimos (long). Redondeo HALF_UP a 2 decimales. */
    public static long toCents(BigDecimal amount) {
        return amount.movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact();
    }

    /** centimos (long) -> BigDecimal con escala 2. */
    public static BigDecimal fromCents(long cents) {
        return BigDecimal.valueOf(cents, 2);
    }
}
