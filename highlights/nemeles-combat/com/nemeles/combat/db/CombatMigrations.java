package com.nemeles.combat.db;

import com.nemeles.core.api.db.Migration;

import java.util.List;

/** Migraciones del namespace "combat" (tipos portables; solo logs append-only). */
public final class CombatMigrations {

    private CombatMigrations() {}

    public static List<Migration> all() {
        return List.of(
            new Migration(1, "death_log", List.of(
                "CREATE TABLE IF NOT EXISTS combat_death_log ("
                    + "id BIGINT NOT NULL, "
                    + "victim VARCHAR(36) NOT NULL, "
                    + "killer VARCHAR(36), "
                    + "cause VARCHAR(32) NOT NULL, "
                    + "permadeath INTEGER NOT NULL DEFAULT 0, "
                    + "dirty_lost_cents BIGINT NOT NULL DEFAULT 0, "
                    + "world VARCHAR(64), x INTEGER, y INTEGER, z INTEGER, "
                    + "created_at BIGINT NOT NULL, "
                    + "PRIMARY KEY (id))"
            ))
        );
    }
}
