package com.nemeles.jobs.level;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Tests de la curva de niveles/XP (lógica pura, sin IO). */
class LevelCurveTest {

    @Test
    void xpToNext_esCuadraticaEnElNivel() {
        // base=100, quad=10  ->  xp(L) = 100*L + 10*L^2
        LevelCurve c = new LevelCurve(100, 10, 50, 0.05);
        assertEquals(110L, c.xpToNext(1));   // 100*1 + 10*1
        assertEquals(240L, c.xpToNext(2));   // 200   + 40
        assertEquals(390L, c.xpToNext(3));   // 300   + 90
    }

    @Test
    void payMultiplier_creceLinealmenteDesdeNivel1() {
        LevelCurve c = new LevelCurve(100, 10, 50, 0.05);
        assertEquals(1.00, c.payMultiplier(1), 1e-9);  // 1 + 0*0.05
        assertEquals(1.05, c.payMultiplier(2), 1e-9);  // 1 + 1*0.05
        assertEquals(1.45, c.payMultiplier(10), 1e-9); // 1 + 9*0.05
    }

    @Test
    void cap_devuelveElTopeConfigurado() {
        assertEquals(50, new LevelCurve(100, 10, 50, 0.05).cap());
    }
}
