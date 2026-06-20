package com.nemeles.core.api.profile;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Carga/guarda perfiles de jugador de forma asincrona, con cache en memoria para los conectados. */
public interface ProfileService {

    /** Carga (o crea si no existe) el perfil del jugador. */
    CompletableFuture<PlayerProfile> load(UUID player);

    /** Perfil en cache (solo jugadores conectados). Lectura sincrona, sin IO. */
    Optional<PlayerProfile> cached(UUID player);

    /** Persiste cambios del perfil. */
    CompletableFuture<Void> save(PlayerProfile profile);
}
