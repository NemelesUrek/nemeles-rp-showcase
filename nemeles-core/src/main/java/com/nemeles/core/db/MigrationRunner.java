package com.nemeles.core.db;

import com.nemeles.core.api.db.DatabaseProvider;
import com.nemeles.core.api.db.Migration;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Aplica migraciones versionadas por namespace. Usa una tabla schema_version (namespace, version) para
 * no re-ejecutar. Cada migracion corre dentro de su propia transaccion (rollback si falla).
 */
public final class MigrationRunner {

    private final DatabaseProvider db;
    private final Logger log;
    private final Map<String, List<Migration>> registered = new LinkedHashMap<>();

    public MigrationRunner(DatabaseProvider db, Logger log) {
        this.db = db;
        this.log = log;
    }

    public void register(String namespace, List<Migration> migrations) {
        List<Migration> sorted = new ArrayList<>(migrations);
        sorted.sort(Comparator.comparingInt(Migration::version));
        registered.put(namespace, sorted);
    }

    public void runAll() {
        try (Connection c = db.dataSource().getConnection()) {
            ensureVersionTable(c);
            for (Map.Entry<String, List<Migration>> entry : registered.entrySet()) {
                applyNamespace(c, entry.getKey(), entry.getValue());
            }
        } catch (SQLException ex) {
            throw new RuntimeException("Fallo ejecutando migraciones: " + ex.getMessage(), ex);
        }
    }

    /** Aplica YA las migraciones de UN namespace (para modulos que las registran tras el arranque del core). */
    public void runNamespace(String namespace) {
        try (Connection c = db.dataSource().getConnection()) {
            ensureVersionTable(c);
            List<Migration> list = registered.get(namespace);
            if (list != null) {
                applyNamespace(c, namespace, list);
            }
        } catch (SQLException ex) {
            throw new RuntimeException("Fallo ejecutando migraciones de '" + namespace + "': " + ex.getMessage(), ex);
        }
    }

    private void ensureVersionTable(Connection c) throws SQLException {
        try (Statement s = c.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS schema_version ("
                    + "namespace VARCHAR(64) NOT NULL, "
                    + "version INTEGER NOT NULL, "
                    + "applied_at BIGINT NOT NULL, "
                    + "PRIMARY KEY (namespace, version))");
        }
    }

    private int currentVersion(Connection c, String namespace) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT MAX(version) FROM schema_version WHERE namespace = ?")) {
            ps.setString(1, namespace);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    int v = rs.getInt(1);
                    return rs.wasNull() ? 0 : v;
                }
                return 0;
            }
        }
    }

    private void applyNamespace(Connection c, String namespace, List<Migration> migrations) throws SQLException {
        int current = currentVersion(c, namespace);
        for (Migration m : migrations) {
            if (m.version() <= current) {
                continue;
            }
            boolean prevAutoCommit = c.getAutoCommit();
            c.setAutoCommit(false);
            try (Statement s = c.createStatement()) {
                for (String raw : m.statements()) {
                    String sql = raw.trim();
                    if (!sql.isEmpty()) {
                        s.execute(sql);
                    }
                }
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO schema_version (namespace, version, applied_at) VALUES (?, ?, ?)")) {
                    ps.setString(1, namespace);
                    ps.setInt(2, m.version());
                    ps.setLong(3, System.currentTimeMillis());
                    ps.executeUpdate();
                }
                c.commit();
                log.info("[DB] Migracion aplicada: " + namespace + " V" + m.version() + " (" + m.name() + ")");
            } catch (SQLException ex) {
                c.rollback();
                throw new SQLException("Migracion fallida " + namespace + " V" + m.version()
                        + ": " + ex.getMessage(), ex);
            } finally {
                c.setAutoCommit(prevAutoCommit);
            }
        }
    }
}
