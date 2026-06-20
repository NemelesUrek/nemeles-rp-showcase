package com.nemeles.core.api.economy;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Servicio de economia dual de NemelesRP. Todas las operaciones de IO son asincronas
 * ({@link CompletableFuture}); el dinero se maneja con {@link BigDecimal} (nunca double salvo en la
 * frontera con Vault). Internamente se persiste como BIGINT de centimos.
 */
public interface EconomyService {

    /** Saldo actual de un tipo de moneda. */
    CompletableFuture<BigDecimal> balance(UUID player, MoneyType type);

    /** Los 4 saldos de golpe (para UI como la billetera del telefono). */
    CompletableFuture<Map<MoneyType, BigDecimal>> balances(UUID player);

    /** Ingresa dinero (origen externo: sueldo, recompensa, spawn). */
    CompletableFuture<TransactionResult> deposit(UUID player, MoneyType type, BigDecimal amount, String reason);

    /** Retira dinero (sink: impuesto, compra, multa). */
    CompletableFuture<TransactionResult> withdraw(UUID player, MoneyType type, BigDecimal amount, String reason);

    /** Transferencia ATOMICA entre jugadores (lockea ambas cuentas en orden de UUID; anti-dupe). */
    CompletableFuture<TransactionResult> transfer(UUID from, MoneyType fromType,
                                                  UUID to, MoneyType toType,
                                                  BigDecimal amount, String reason);

    /** Lavado: mueve SUCIO -> LIMPIO aplicando una comision; emite MoneyLaunderedEvent. */
    CompletableFuture<TransactionResult> launder(UUID player, BigDecimal amount, BigDecimal feeRatio, String channel);

    /** Que MoneyType ve la interfaz clasica de Vault (normalmente EFECTIVO). */
    MoneyType legacyVaultType();

    // ─── Trazabilidad policial ("seguir el dinero") ────────────────────────
    // DEFAULT para no romper otras implementaciones de EconomyService (devuelven vacio si no lo soportan).

    /**
     * Historial de lotes de dinero marcado (lavado) del jugador, del mas reciente al mas antiguo, limitado
     * a {@code limit}. Incluye los ya limpiados en cajero (con su fecha). Lectura async fuera del hilo
     * principal. Por defecto: lista vacia.
     */
    default CompletableFuture<List<MarkedTrace>> markedHistory(UUID owner, int limit) {
        return CompletableFuture.completedFuture(List.of());
    }

    /**
     * Resumen del dinero rastreable del jugador en UNIDADES. Claves: {@code "marcado_sin_limpiar"} (dinero
     * lavado que aun no paso por cajero) y {@code "ya_limpiado"} (dinero que ya se limpio en cajero).
     * Lectura async fuera del hilo principal. Por defecto: mapa vacio.
     */
    default CompletableFuture<Map<String, BigDecimal>> markedSummary(UUID owner) {
        return CompletableFuture.completedFuture(Map.of());
    }
}
