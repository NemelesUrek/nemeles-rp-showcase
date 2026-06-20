package com.nemeles.core.economy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/** Acceso a la tabla core_balances. */
final class BalanceDao {

    void loadOrCreate(Connection c, Account account) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT efectivo_cents, banco_cents, sucio_cents, limpio_cents FROM core_balances WHERE uuid = ?")) {
            ps.setString(1, account.uuid().toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    account.setAll(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4));
                    return;
                }
            }
        }
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO core_balances (uuid, efectivo_cents, banco_cents, sucio_cents, limpio_cents, updated_at) "
                        + "VALUES (?, 0, 0, 0, 0, ?)")) {
            ps.setString(1, account.uuid().toString());
            ps.setLong(2, System.currentTimeMillis());
            ps.executeUpdate();
        }
    }

    void update(Connection c, Account a) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE core_balances SET efectivo_cents = ?, banco_cents = ?, sucio_cents = ?, limpio_cents = ?, "
                        + "updated_at = ? WHERE uuid = ?")) {
            ps.setLong(1, a.efectivo());
            ps.setLong(2, a.banco());
            ps.setLong(3, a.sucio());
            ps.setLong(4, a.limpio());
            ps.setLong(5, System.currentTimeMillis());
            ps.setString(6, a.uuid().toString());
            ps.executeUpdate();
        }
    }
}
