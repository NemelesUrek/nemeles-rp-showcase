package com.nemeles.core.profile;

import com.nemeles.core.api.profile.PlayerProfile;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Implementacion en memoria de un perfil. Se persiste via {@link ProfileDao}. */
final class ProfileImpl implements PlayerProfile {

    private final UUID uuid;
    private final long createdAt;
    private volatile String lastKnownName;
    private volatile boolean downed;
    private final Set<UUID> revealed = ConcurrentHashMap.newKeySet();

    ProfileImpl(UUID uuid, String lastKnownName, long createdAt, boolean downed) {
        this.uuid = uuid;
        this.lastKnownName = lastKnownName;
        this.createdAt = createdAt;
        this.downed = downed;
    }

    @Override public UUID uuid() { return uuid; }
    @Override public String lastKnownName() { return lastKnownName; }
    @Override public boolean isIdentityRevealed(UUID toWhom) { return revealed.contains(toWhom); }
    @Override public void setIdentityRevealed(UUID toWhom, boolean reveal) {
        if (reveal) revealed.add(toWhom); else revealed.remove(toWhom);
    }
    @Override public boolean isDowned() { return downed; }
    @Override public void setDowned(boolean downed) { this.downed = downed; }
    @Override public long createdAt() { return createdAt; }

    // ─── uso interno del modulo ──────────────────────────────
    void setLastKnownName(String name) { this.lastKnownName = name; }
    Set<UUID> revealedTargets() { return revealed; }
}
