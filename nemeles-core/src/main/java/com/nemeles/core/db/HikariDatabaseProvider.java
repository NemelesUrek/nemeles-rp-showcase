package com.nemeles.core.db;

import com.nemeles.core.api.db.DatabaseProvider;
import com.nemeles.core.api.db.Migration;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.Plugin;

import javax.sql.DataSource;
import java.io.File;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * Implementacion de {@link DatabaseProvider} con HikariCP. El MISMO codigo sirve para SQLite (ahora)
 * y MySQL/MariaDB (futuro): solo cambia config.yml. En SQLite se fuerza pool=1 + WAL (single-writer).
 */
public final class HikariDatabaseProvider implements DatabaseProvider {

    private final HikariDataSource dataSource;
    private final ExecutorService executor;
    private final boolean sqlite;
    private final MigrationRunner runner;

    public HikariDatabaseProvider(Plugin plugin) {
        FileConfiguration cfg = plugin.getConfig();
        String type = cfg.getString("database.type", "sqlite").toLowerCase(Locale.ROOT);
        this.sqlite = !type.equals("mysql") && !type.equals("mariadb");

        HikariConfig hc = new HikariConfig();
        hc.setPoolName("NemelesCore-DB");

        if (sqlite) {
            File dbFile = new File(plugin.getDataFolder(), cfg.getString("database.sqlite.file", "data.db"));
            dbFile.getParentFile().mkdirs();
            hc.setDriverClassName("org.sqlite.JDBC");
            hc.setJdbcUrl("jdbc:sqlite:" + dbFile.getAbsolutePath());
            // SQLite es single-writer: un solo hilo escritor evita 'database is locked'.
            hc.setMaximumPoolSize(1);
            // PRAGMAs aplicados por xerial como propiedades de conexion.
            hc.addDataSourceProperty("journal_mode", "WAL");
            hc.addDataSourceProperty("synchronous", "NORMAL");
            hc.addDataSourceProperty("busy_timeout", "5000");
            hc.addDataSourceProperty("foreign_keys", "true");
        } else {
            throw new IllegalStateException("database.type=" + type
                    + " aun no esta soportado en esta version (proximo milestone). Usa 'sqlite'.");
        }

        this.dataSource = new HikariDataSource(hc);
        int threads = sqlite ? 1 : Math.max(2, cfg.getInt("database.pool.maximum-pool-size", 10));
        this.executor = Executors.newFixedThreadPool(threads, namedThreadFactory());
        this.runner = new MigrationRunner(this, plugin.getLogger());
    }

    private ThreadFactory namedThreadFactory() {
        return runnable -> {
            Thread t = new Thread(runnable, "NemelesCore-DB");
            t.setDaemon(true);
            return t;
        };
    }

    @Override public DataSource dataSource() { return dataSource; }
    @Override public Executor dbExecutor() { return executor; }
    @Override public boolean isSqlite() { return sqlite; }

    @Override
    public void registerMigrations(String namespace, List<Migration> migrations) {
        runner.register(namespace, migrations);
        runner.runNamespace(namespace); // aplica de inmediato (soporta modulos que registran tras el arranque)
    }

    /** Aplica todas las migraciones registradas (llamar en onEnable, antes de habilitar modulos). */
    public void runMigrations() {
        runner.runAll();
    }

    /** Cierra el pool y el executor (onDisable). */
    public void shutdown() {
        executor.shutdown();
        if (!dataSource.isClosed()) {
            dataSource.close();
        }
    }
}
