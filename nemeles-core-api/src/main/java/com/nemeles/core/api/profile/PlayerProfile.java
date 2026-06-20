package com.nemeles.core.api.profile;

import java.util.UUID;

/**
 * Perfil persistente de un jugador en NemelesRP. Indexado por UUID (en offline-mode el UUID es la
 * verdad estable; el nombre puede cambiar, por eso se guarda {@link #lastKnownName()}).
 */
public interface PlayerProfile {

    UUID uuid();

    /** Ultimo nombre conocido (offline-mode: el nombre NO es clave fiable). */
    String lastKnownName();

    /** Sistema de "nombres ocultos": si este jugador ha revelado su identidad a {@code toWhom}. */
    boolean isIdentityRevealed(UUID toWhom);

    void setIdentityRevealed(UUID toWhom, boolean revealed);

    /** Estado de muerte = "derribado" (downed); un paramedico puede revivir. */
    boolean isDowned();

    void setDowned(boolean downed);

    /** Epoch millis de creacion del perfil. */
    long createdAt();
}
