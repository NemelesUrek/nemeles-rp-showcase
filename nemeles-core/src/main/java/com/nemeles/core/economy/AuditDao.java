package com.nemeles.core.economy;

import com.nemeles.core.api.economy.MoneyType;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;

/** Acceso append-only a core_audit_log (registro inmutable de toda operacion de dinero). */
final class AuditDao {

    void insert(Connection c, long txId, String fromUuid, String toUuid, MoneyType type,
                long amountCents, String opType, String reason) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO core_audit_log (tx_id, from_uuid, to_uuid, money_type, amount_cents, type, reason, ts) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
            ps.setLong(1, txId);
            if (fromUuid == null) ps.setNull(2, Types.VARCHAR); else ps.setString(2, fromUuid);
            if (toUuid == null) ps.setNull(3, Types.VARCHAR); else ps.setString(3, toUuid);
            ps.setString(4, type.name());
            ps.setLong(5, amountCents);
            ps.setString(6, opType);
            if (reason == null) ps.setNull(7, Types.VARCHAR); else ps.setString(7, reason);
            ps.setLong(8, System.currentTimeMillis());
            ps.executeUpdate();
        }
    }
}
