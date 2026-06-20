package com.nemeles.core.economy;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests de {@link DualEconomyService#interestCents}, la lógica PURA del interés de ahorros.
 * Todos los montos van en céntimos (la economía maneja el dinero como long de céntimos).
 */
class DualEconomyServiceTest {

    private static final BigDecimal RATE_5 = new BigDecimal("0.05");
    private static final long NO_CAP = 0L; // sin tope de pago
    private static final long NO_MIN = 0L; // sin saldo mínimo

    @Test
    void interes_basico() {
        // 100,000 céntimos ($1000) al 5% = 5,000 céntimos
        assertEquals(5_000L,
                DualEconomyService.interestCents(100_000L, RATE_5, 10_000_000L, NO_CAP, NO_MIN));
    }

    @Test
    void interes_redondeaHaciaAbajo_nuncaAFavorDelJugador() {
        // 100,001 * 0.05 = 5,000.05  ->  floor -> 5,000 (NO 5,001)
        assertEquals(5_000L,
                DualEconomyService.interestCents(100_001L, RATE_5, 10_000_000L, NO_CAP, NO_MIN));
    }

    @Test
    void interes_topeDePrincipalLoHaceLineal() {
        // Banco 2,000,000 pero el cap de principal es 1,000,000 -> solo ese principal genera interés.
        assertEquals(50_000L,
                DualEconomyService.interestCents(2_000_000L, RATE_5, 1_000_000L, NO_CAP, NO_MIN));
    }

    @Test
    void interes_maxPayoutEsTechoDuro() {
        // raw = 1,000,000 * 0.10 = 100,000, pero el tope de pago es 50,000.
        assertEquals(50_000L,
                DualEconomyService.interestCents(1_000_000L, new BigDecimal("0.10"), 10_000_000L, 50_000L, NO_MIN));
    }

    @Test
    void interes_ceroSiBancoBajoElMinimo() {
        // Banco (10,000) por debajo del mínimo (20,000) -> no genera.
        assertEquals(0L,
                DualEconomyService.interestCents(10_000L, RATE_5, 10_000_000L, NO_CAP, 20_000L));
    }

    @Test
    void interes_ceroSiRateNoEsPositiva() {
        assertEquals(0L, DualEconomyService.interestCents(100_000L, BigDecimal.ZERO, 10_000_000L, NO_CAP, NO_MIN));
        assertEquals(0L, DualEconomyService.interestCents(100_000L, null, 10_000_000L, NO_CAP, NO_MIN));
    }
}
