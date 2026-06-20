package com.nemeles.core.api.economy;

import java.math.BigDecimal;

/**
 * Resultado de una operacion economica. Si {@code success} es false, {@code errorCode} indica el motivo
 * (ej. "FONDOS_INSUFICIENTES", "MONTO_INVALIDO", "CUENTA_BLOQUEADA").
 */
public record TransactionResult(boolean success, BigDecimal newBalance, long txId, String errorCode) {

    public static TransactionResult ok(BigDecimal newBalance, long txId) {
        return new TransactionResult(true, newBalance, txId, null);
    }

    public static TransactionResult fail(String errorCode) {
        return new TransactionResult(false, null, -1L, errorCode);
    }
}
