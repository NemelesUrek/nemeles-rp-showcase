package com.nemeles.core.profile;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

/** Acceso a core_profiles y core_identity_reveals. */
final class ProfileDao {

    ProfileImpl load(Connection c, UUID uuid) throws SQLException {
        ProfileImpl profile = null;
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT last_known_name, created_at, is_downed FROM core_profiles WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    profile = new ProfileImpl(uuid, rs.getString(1), rs.getLong(2), rs.getInt(3) != 0);
                }
            }
        }
        if (profile == null) {
            return null;
        }
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT revealed_uuid FROM core_identity_reveals WHERE revealer_uuid = ?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    try {
                        profile.revealedTargets().add(UUID.fromString(rs.getString(1)));
                    } catch (IllegalArgumentException ignored) {
                        // uuid corrupto: lo saltamos
                    }
                }
            }
        }
        return profile;
    }

    void insert(Connection c, ProfileImpl p, long now) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO core_profiles (uuid, last_known_name, created_at, last_seen, is_downed) "
                        + "VALUES (?, ?, ?, ?, ?)")) {
            ps.setString(1, p.uuid().toString());
            ps.setString(2, p.lastKnownName());
            ps.setLong(3, p.createdAt());
            ps.setLong(4, now);
            ps.setInt(5, p.isDowned() ? 1 : 0);
            ps.executeUpdate();
        }
    }

    void update(Connection c, ProfileImpl p, long now) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE core_profiles SET last_known_name = ?, last_seen = ?, is_downed = ? WHERE uuid = ?")) {
            ps.setString(1, p.lastKnownName());
            ps.setLong(2, now);
            ps.setInt(3, p.isDowned() ? 1 : 0);
            ps.setString(4, p.uuid().toString());
            ps.executeUpdate();
        }
        // Resync de identidades reveladas (full replace: simple y correcto a esta escala).
        try (PreparedStatement del = c.prepareStatement(
                "DELETE FROM core_identity_reveals WHERE revealer_uuid = ?")) {
            del.setString(1, p.uuid().toString());
            del.executeUpdate();
        }
        if (!p.revealedTargets().isEmpty()) {
            try (PreparedStatement ins = c.prepareStatement(
                    "INSERT INTO core_identity_reveals (revealer_uuid, revealed_uuid, ts) VALUES (?, ?, ?)")) {
                for (UUID target : p.revealedTargets()) {
                    ins.setString(1, p.uuid().toString());
                    ins.setString(2, target.toString());
                    ins.setLong(3, now);
                    ins.addBatch();
                }
                ins.executeBatch();
            }
        }
    }
}
