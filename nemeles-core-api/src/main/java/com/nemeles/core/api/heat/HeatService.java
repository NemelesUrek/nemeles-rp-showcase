package com.nemeles.core.api.heat;

import java.util.UUID;

/**
 * CALOR: cuanto te vigila la ciudad (jugador 0-100, faccion 0-100, negocio 0-100).
 * Sube con el crimen (ventas de droga, asesinatos, arrestos, lavado) y decae con el tiempo.
 * Las consecuencias (comision de lavado, inspecciones, alertas a la policia) las aplica el
 * modulo nemeles-heat. Otros modulos lo consultan/alimentan en try/catch.
 */
public interface HeatService {

    double playerHeat(UUID player);
    double factionHeat(int factionId);
    double businessHeat(int businessId);

    /** Venta de droga (lo llama el modulo de drogas al vender). */
    void onDrugSale(UUID player, int bags);

    /**
     * Venta de droga con tipo (oferta/demanda SERVER-WIDE). Sube el calor del jugador como
     * {@link #onDrugSale(UUID, int)} Y SATURA el mercado de ese tipo de droga ('weed','coca'),
     * que baja el precio del dia y se recupera al dia siguiente.
     * @param drug clave del mercado ('weed', 'coca'); units = unidades vendidas (bolsas/gramos).
     */
    default void onDrugSale(UUID player, String drug, int units) { onDrugSale(player, units); }

    /**
     * Multiplicador SERVER-WIDE de precio para esta droga segun cuanto se ha vendido HOY
     * (acotado, p.ej. 0.5x..1.2x). 1.0 = precio base; &lt;1 = mercado saturado; &gt;1 = mercado seco.
     * El modulo de droga lo aplica ANTES del peaje territorial.
     */
    default double priceMultiplier(String drug) { return 1.0; }

    /** Lavado de dinero. businessId = -1 si fue en la calle/cajero. amountUnits en unidades ($). */
    void onLaunder(UUID player, int businessId, double amountUnits);

    /** Extra de comision de lavado que arrastra ESTE jugador por su calor (0.0 si esta frio). */
    double launderFeeExtra(UUID player);

    /** Epoch ms hasta el que este negocio esta INSPECCIONADO (lavado bloqueado); 0 = libre. */
    long businessLockedUntil(int businessId);
}
