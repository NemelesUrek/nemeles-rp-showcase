package com.nemeles.jobs.db;

import com.nemeles.core.api.db.Migration;

import java.util.List;

/** Migraciones del namespace "jobs" (tipos portables, sin DECIMAL/AUTOINCREMENT, como el core). */
public final class JobsMigrations {

    private JobsMigrations() {}

    public static List<Migration> all() {
        return List.of(
            new Migration(1, "init", List.of(
                "CREATE TABLE IF NOT EXISTS jobs_levels ("
                    + "uuid VARCHAR(36) NOT NULL, "
                    + "job VARCHAR(32) NOT NULL, "
                    + "level INTEGER NOT NULL DEFAULT 1, "
                    + "xp BIGINT NOT NULL DEFAULT 0, "
                    + "total_xp BIGINT NOT NULL DEFAULT 0, "
                    + "joined_at BIGINT NOT NULL, "
                    + "PRIMARY KEY (uuid, job))",
                "CREATE INDEX idx_jobs_levels_job ON jobs_levels (job, total_xp)"
            )),

            // V2: decadencia por inactividad (last_used) y suelo que protege perks (peak_level).
            new Migration(2, "skills_decay", List.of(
                "ALTER TABLE jobs_levels ADD COLUMN last_used BIGINT NOT NULL DEFAULT 0",
                "ALTER TABLE jobs_levels ADD COLUMN peak_level INTEGER NOT NULL DEFAULT 1"
            )),

            // V3: plantas de marihuana vivas (la fuente de verdad es la BD: sobrevive reinicios).
            new Migration(3, "weed_plants", List.of(
                "CREATE TABLE IF NOT EXISTS jobs_plants ("
                    + "id VARCHAR(36) NOT NULL, "
                    + "owner VARCHAR(36) NOT NULL, "
                    + "world VARCHAR(48) NOT NULL, "
                    + "x INTEGER NOT NULL, "
                    + "y INTEGER NOT NULL, "
                    + "z INTEGER NOT NULL, "
                    + "planted_at BIGINT NOT NULL, "
                    + "stage INTEGER NOT NULL DEFAULT 0, "
                    + "state VARCHAR(16) NOT NULL DEFAULT 'GROWING', "
                    + "PRIMARY KEY (id))",
                "CREATE INDEX idx_jobs_plants_owner ON jobs_plants (owner)"
            )),

            // V4: EMPLEO (un trabajo por jugador; capa de identidad economica sobre las habilidades).
            new Migration(4, "employment", List.of(
                "CREATE TABLE IF NOT EXISTS player_job ("
                    + "uuid VARCHAR(36) NOT NULL, "
                    + "job VARCHAR(32), "
                    + "last_change BIGINT NOT NULL DEFAULT 0, "
                    + "PRIMARY KEY (uuid))"
            )),

            // V5: PERKS (1 de 2 cada 10 niveles por habilidad).
            new Migration(5, "perks", List.of(
                "CREATE TABLE IF NOT EXISTS jobs_perks ("
                    + "uuid VARCHAR(36) NOT NULL, "
                    + "skill VARCHAR(32) NOT NULL, "
                    + "tier INTEGER NOT NULL, "
                    + "choice CHAR(1) NOT NULL, "
                    + "chosen_at BIGINT NOT NULL, "
                    + "PRIMARY KEY (uuid, skill, tier))",
                "CREATE INDEX idx_jobs_perks_uuid ON jobs_perks (uuid)"
            )),

            // V6: cuidado de plantas (fertilizante con minijuego; sin cuidar pueden pudrirse).
            new Migration(6, "weed_care", List.of(
                "ALTER TABLE jobs_plants ADD COLUMN cared INTEGER NOT NULL DEFAULT 0",
                "ALTER TABLE jobs_plants ADD COLUMN quality INTEGER NOT NULL DEFAULT 0"
            ))
        );
    }
}
