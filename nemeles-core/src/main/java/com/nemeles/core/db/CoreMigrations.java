package com.nemeles.core.db;

import com.nemeles.core.api.db.Migration;

import java.util.List;

/**
 * Migraciones del namespace "core". Tipos elegidos para ser portables SQLite/MySQL/MariaDB:
 * VARCHAR(n) para UUIDs/strings, BIGINT para dinero (centimos) y tiempos, INTEGER para booleanos.
 * Se evita DECIMAL (no portable) y AUTOINCREMENT (los tx_id se generan en codigo).
 */
public final class CoreMigrations {

    private CoreMigrations() {}

    public static List<Migration> all() {
        return List.of(
            new Migration(1, "init", List.of(
                "CREATE TABLE IF NOT EXISTS core_profiles ("
                    + "uuid VARCHAR(36) PRIMARY KEY, "
                    + "last_known_name VARCHAR(32) NOT NULL, "
                    + "created_at BIGINT NOT NULL, "
                    + "last_seen BIGINT NOT NULL, "
                    + "is_downed INTEGER NOT NULL DEFAULT 0)",

                "CREATE TABLE IF NOT EXISTS core_balances ("
                    + "uuid VARCHAR(36) PRIMARY KEY, "
                    + "efectivo_cents BIGINT NOT NULL DEFAULT 0, "
                    + "banco_cents BIGINT NOT NULL DEFAULT 0, "
                    + "sucio_cents BIGINT NOT NULL DEFAULT 0, "
                    + "limpio_cents BIGINT NOT NULL DEFAULT 0, "
                    + "updated_at BIGINT NOT NULL DEFAULT 0)",

                "CREATE TABLE IF NOT EXISTS core_audit_log ("
                    + "tx_id BIGINT PRIMARY KEY, "
                    + "from_uuid VARCHAR(36), "
                    + "to_uuid VARCHAR(36), "
                    + "money_type VARCHAR(16) NOT NULL, "
                    + "amount_cents BIGINT NOT NULL, "
                    + "type VARCHAR(16) NOT NULL, "
                    + "reason VARCHAR(255), "
                    + "ts BIGINT NOT NULL)",

                "CREATE INDEX idx_audit_from ON core_audit_log (from_uuid, ts)",
                "CREATE INDEX idx_audit_to ON core_audit_log (to_uuid, ts)",

                "CREATE TABLE IF NOT EXISTS core_identity_reveals ("
                    + "revealer_uuid VARCHAR(36) NOT NULL, "
                    + "revealed_uuid VARCHAR(36) NOT NULL, "
                    + "ts BIGINT NOT NULL, "
                    + "PRIMARY KEY (revealer_uuid, revealed_uuid))"
            )),

            // V2: dinero limpio "marcado" (rastreable) generado al lavar. Por ahora solo se almacena;
            // base para el futuro cajero (lo "limpia") y para la policia (lo consulta por su codigo).
            new Migration(2, "marked_money", List.of(
                "CREATE TABLE IF NOT EXISTS core_marked_money ("
                    + "code VARCHAR(32) PRIMARY KEY, "
                    + "owner_uuid VARCHAR(36) NOT NULL, "
                    + "amount_cents BIGINT NOT NULL, "
                    + "source_tx_id BIGINT, "
                    + "created_at BIGINT NOT NULL, "
                    + "cleared INTEGER NOT NULL DEFAULT 0)",
                "CREATE INDEX idx_marked_owner ON core_marked_money (owner_uuid, cleared)"
            )),

            // V3: trazabilidad policial ("seguir el dinero"). Guarda el ORIGEN (channel: business:ID /
            // fachada:ID...) y la ZONA del lavado, y registra cuando/con-que-tx se LIMPIO en el cajero.
            // Cada ALTER ... ADD COLUMN va en su propia sentencia (SQLite no soporta multiples por ALTER).
            // Las filas viejas quedan con estas columnas en NULL sin romper markedTotal/clearMarked (FIFO).
            new Migration(3, "marked_money_trace", List.of(
                "ALTER TABLE core_marked_money ADD COLUMN channel VARCHAR(48)",
                "ALTER TABLE core_marked_money ADD COLUMN zona VARCHAR(48)",
                "ALTER TABLE core_marked_money ADD COLUMN cleared_tx BIGINT",
                "ALTER TABLE core_marked_money ADD COLUMN cleared_at BIGINT"
            )),

            // V4: interes de los AHORROS (saldo BANCO). last_paid_at = ultimo pago (epoch ms);
            // total_paid_cents = acumulado historico (estadistica / posible tope de por vida futuro).
            new Migration(4, "interest", List.of(
                "CREATE TABLE IF NOT EXISTS core_interest ("
                    + "uuid VARCHAR(36) PRIMARY KEY, "
                    + "last_paid_at BIGINT NOT NULL, "
                    + "total_paid_cents BIGINT NOT NULL DEFAULT 0)"
            ))
        );
    }
}
