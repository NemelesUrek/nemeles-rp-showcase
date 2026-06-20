package com.nemeles.territories.db;

import com.nemeles.core.api.db.Migration;

import java.util.List;

/** Migraciones del namespace "territory" (tipos portables, sin DECIMAL ni AUTOINCREMENT). */
public final class TerritoriesMigrations {

    private TerritoriesMigrations() {}

    public static List<Migration> all() {
        return List.of(
            new Migration(1, "meta", List.of(
                "CREATE TABLE IF NOT EXISTS territory_meta ("
                    + "id INTEGER NOT NULL, "
                    + "name VARCHAR(48) NOT NULL, "
                    + "world VARCHAR(64) NOT NULL, "
                    + "tier INTEGER NOT NULL DEFAULT 1, "
                    + "owner_faction INTEGER NOT NULL DEFAULT -1, "
                    + "income_cents BIGINT NOT NULL DEFAULT 0, "
                    + "state VARCHAR(16) NOT NULL DEFAULT 'ACTIVE', "
                    + "captured_at BIGINT NOT NULL DEFAULT 0, "
                    + "shield_until BIGINT NOT NULL DEFAULT 0, "
                    + "last_income_at BIGINT NOT NULL DEFAULT 0, "
                    + "last_upkeep_at BIGINT NOT NULL DEFAULT 0, "
                    + "PRIMARY KEY (id))"
            )),
            new Migration(2, "chunk", List.of(
                "CREATE TABLE IF NOT EXISTS territory_chunk ("
                    + "territory_id INTEGER NOT NULL, "
                    + "world VARCHAR(64) NOT NULL, "
                    + "chunk_x INTEGER NOT NULL, "
                    + "chunk_z INTEGER NOT NULL, "
                    + "PRIMARY KEY (world, chunk_x, chunk_z))",
                "CREATE INDEX IF NOT EXISTS idx_terr_chunk_tid ON territory_chunk (territory_id)"
            )),
            new Migration(3, "history", List.of(
                "CREATE TABLE IF NOT EXISTS territory_history ("
                    + "id BIGINT NOT NULL, "
                    + "territory_id INTEGER NOT NULL, "
                    + "from_faction INTEGER NOT NULL DEFAULT -1, "
                    + "to_faction INTEGER NOT NULL, "
                    + "reason VARCHAR(32), "
                    + "at_ms BIGINT NOT NULL, "
                    + "PRIMARY KEY (id))",
                "CREATE INDEX IF NOT EXISTS idx_terr_hist_tid ON territory_history (territory_id)"
            )),
            new Migration(4, "war", List.of(
                "CREATE TABLE IF NOT EXISTS territory_war ("
                    + "id BIGINT NOT NULL, "
                    + "territory_id INTEGER NOT NULL, "
                    + "attacker_faction INTEGER NOT NULL, "
                    + "defender_faction INTEGER NOT NULL DEFAULT -1, "
                    + "pot_cents BIGINT NOT NULL DEFAULT 0, "
                    + "prep_end_ms BIGINT NOT NULL DEFAULT 0, "
                    + "window_end_ms BIGINT NOT NULL DEFAULT 0, "
                    + "state VARCHAR(16) NOT NULL DEFAULT 'PREP', "
                    + "PRIMARY KEY (id))",
                "CREATE INDEX IF NOT EXISTS idx_terr_war_tid ON territory_war (territory_id)",
                "CREATE INDEX IF NOT EXISTS idx_terr_war_state ON territory_war (state)"
            )),
            // v5: influencia/lealtad acumulada por tiempo controlado (sube la dificultad de captura).
            new Migration(5, "influence", List.of(
                "ALTER TABLE territory_meta ADD COLUMN influence_units BIGINT NOT NULL DEFAULT 0"
            ))
        );
    }
}
