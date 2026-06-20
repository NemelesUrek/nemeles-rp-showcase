package com.nemeles.core.api.faction;

/** Resultado de una operación de facción (espejo de TransactionResult). */
public record FactionResult(boolean success, int factionId, String errorCode) {

    public static FactionResult ok(int factionId) {
        return new FactionResult(true, factionId, null);
    }

    public static FactionResult fail(String errorCode) {
        return new FactionResult(false, -1, errorCode);
    }
}
