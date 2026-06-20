package com.nemeles.core.api.territory;

/** Resultado de una operacion de territorio (espejo de FactionResult). */
public record TerritoryResult(boolean success, int territoryId, String errorCode) {

    public static TerritoryResult ok(int territoryId) { return new TerritoryResult(true, territoryId, null); }

    public static TerritoryResult fail(String errorCode) { return new TerritoryResult(false, -1, errorCode); }
}
