package com.nemeles.core.profile;

import com.nemeles.core.api.db.DatabaseProvider;
import com.nemeles.core.api.profile.PlayerProfile;
import com.nemeles.core.api.profile.ProfileService;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/** Servicio de perfiles con cache en memoria para los jugadores conectados. */
public final class ProfileServiceImpl implements ProfileService {

    @FunctionalInterface
    private interface SqlSupplier<T> {
        T get() throws SQLException;
    }

    private final DatabaseProvider db;
    private final Logger log;
    private final java.util.Map<UUID, ProfileImpl> cache = new ConcurrentHashMap<>();
    private final ProfileDao dao = new ProfileDao();

    public ProfileServiceImpl(DatabaseProvider db, Logger log) {
        this.db = db;
        this.log = log;
    }

    private <T> CompletableFuture<T> supply(SqlSupplier<T> supplier) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return supplier.get();
            } catch (SQLException e) {
                throw new CompletionException(e);
            }
        }, db.dbExecutor());
    }

    @Override
    public CompletableFuture<PlayerProfile> load(UUID player) {
        return supply(() -> loadOrCreate(player, null));
    }

    @Override
    public Optional<PlayerProfile> cached(UUID player) {
        return Optional.ofNullable(cache.get(player));
    }

    @Override
    public CompletableFuture<Void> save(PlayerProfile profile) {
        return supply(() -> {
            if (profile instanceof ProfileImpl p) {
                try (Connection c = db.dataSource().getConnection()) {
                    dao.update(c, p, System.currentTimeMillis());
                }
            }
            return null;
        });
    }

    // ─── usados por el listener ──────────────────────────────

    public CompletableFuture<PlayerProfile> handleJoin(UUID player, String name) {
        return supply(() -> loadOrCreate(player, name));
    }

    public CompletableFuture<Void> handleQuit(UUID player) {
        return supply(() -> {
            ProfileImpl p = cache.remove(player);
            if (p != null) {
                try (Connection c = db.dataSource().getConnection()) {
                    dao.update(c, p, System.currentTimeMillis());
                }
            }
            return null;
        });
    }

    private PlayerProfile loadOrCreate(UUID uuid, String name) throws SQLException {
        ProfileImpl cached = cache.get(uuid);
        try (Connection c = db.dataSource().getConnection()) {
            if (cached == null) {
                ProfileImpl p = dao.load(c, uuid);
                if (p == null) {
                    p = new ProfileImpl(uuid, name != null ? name : "?", System.currentTimeMillis(), false);
                    dao.insert(c, p, System.currentTimeMillis());
                } else if (name != null) {
                    p.setLastKnownName(name);
                    dao.update(c, p, System.currentTimeMillis());
                }
                cache.put(uuid, p);
                return p;
            }
            if (name != null) {
                cached.setLastKnownName(name);
                dao.update(c, cached, System.currentTimeMillis());
            }
            return cached;
        }
    }
}
