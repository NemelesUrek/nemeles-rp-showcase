package com.nemeles.npcai.db;

import com.nemeles.core.api.db.Migration;

import java.util.List;

/** Migraciones del namespace "npcai" (afinidad + memoria de largo plazo de los NPCs). */
public final class AffinityMigrations {

    private AffinityMigrations() { }

    public static List<Migration> all() {
        return List.of(
            new Migration(1, "affinity", List.of(
                "CREATE TABLE IF NOT EXISTS npcai_affinity ("
                    + "persona VARCHAR(32) NOT NULL, "
                    + "player_uuid VARCHAR(36) NOT NULL, "
                    + "points INTEGER NOT NULL DEFAULT 0, "
                    + "updated_at BIGINT NOT NULL DEFAULT 0, "
                    + "PRIMARY KEY (persona, player_uuid))"
            )),
            // V2: memoria de largo plazo. El NPC recuerda DE QUE habló contigo (resumen breve) entre sesiones.
            new Migration(2, "memory", List.of(
                "CREATE TABLE IF NOT EXISTS npcai_memory ("
                    + "persona VARCHAR(32) NOT NULL, "
                    + "player_uuid VARCHAR(36) NOT NULL, "
                    + "memory TEXT, "
                    + "updated_at BIGINT NOT NULL DEFAULT 0, "
                    + "PRIMARY KEY (persona, player_uuid))"
            )),
            // V3: easter egg "alma gemela". Dueno UNICO por NPC (npcai_bond) + progreso por jugador (npcai_bond_progress).
            new Migration(3, "bond", List.of(
                "CREATE TABLE IF NOT EXISTS npcai_bond ("
                    + "persona VARCHAR(32) NOT NULL, "
                    + "owner_uuid VARCHAR(36) NOT NULL, "
                    + "owner_name VARCHAR(32), "
                    + "bonded_at BIGINT NOT NULL DEFAULT 0, "
                    + "PRIMARY KEY (persona))",
                "CREATE TABLE IF NOT EXISTS npcai_bond_progress ("
                    + "persona VARCHAR(32) NOT NULL, "
                    + "player_uuid VARCHAR(36) NOT NULL, "
                    + "turns INTEGER NOT NULL DEFAULT 0, "
                    + "phrase_idx INTEGER NOT NULL DEFAULT 0, "
                    + "hinted INTEGER NOT NULL DEFAULT 0, "
                    + "PRIMARY KEY (persona, player_uuid))"
            ))
        );
    }
}
