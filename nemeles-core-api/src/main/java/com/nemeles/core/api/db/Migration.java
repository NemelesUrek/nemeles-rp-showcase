package com.nemeles.core.api.db;

import java.util.Collections;
import java.util.List;

/**
 * Una migracion de esquema versionada. Cada modulo aporta una lista; el core las aplica en orden
 * dentro de una transaccion y registra la version en la tabla schema_version (una sola vez).
 *
 * <p>Tipos portables SQLite/MySQL recomendados: VARCHAR(n), BIGINT, INTEGER. Evitar DECIMAL
 * (no portable a SQLite) y AUTOINCREMENT (difiere entre motores).</p>
 */
public final class Migration {

    private final int version;
    private final String name;
    private final List<String> statements;

    public Migration(int version, String name, List<String> statements) {
        this.version = version;
        this.name = name;
        this.statements = Collections.unmodifiableList(statements);
    }

    public int version() { return version; }
    public String name() { return name; }
    public List<String> statements() { return statements; }
}
