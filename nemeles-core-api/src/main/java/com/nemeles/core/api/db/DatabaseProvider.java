package com.nemeles.core.api.db;

import javax.sql.DataSource;
import java.util.List;
import java.util.concurrent.Executor;

/**
 * Capa de datos abstraida del nucleo. Es indiferente al motor (SQLite ahora, MySQL/MariaDB despues):
 * los modulos piden el {@link DataSource} y ejecutan SUS consultas SIEMPRE en {@link #dbExecutor()}
 * (nunca en el hilo principal de Paper).
 */
public interface DatabaseProvider {

    /** Pool de conexiones (HikariCP). No cerrar: lo gestiona el core. */
    DataSource dataSource();

    /** Executor dedicado para IO de BD. Toda query debe correr aqui, fuera del hilo principal. */
    Executor dbExecutor();

    /** true si el motor activo es SQLite (util para PRAGMAs/dialecto). */
    boolean isSqlite();

    /**
     * Registra las migraciones de un modulo bajo un namespace propio (ej. "core", "phone", "factions").
     * Se aplican en orden de version, una sola vez cada una, al arrancar.
     */
    void registerMigrations(String namespace, List<Migration> migrations);
}
