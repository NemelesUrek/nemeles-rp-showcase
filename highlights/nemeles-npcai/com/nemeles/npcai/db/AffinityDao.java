package com.nemeles.npcai.db;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

public final class AffinityDao {

    private final DataSource ds;

    public AffinityDao(DataSource ds) { this.ds = ds; }

    public int load(String persona, UUID id) throws SQLException {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT points FROM npcai_affinity WHERE persona = ? AND player_uuid = ?")) {
            ps.setString(1, persona);
            ps.setString(2, id.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getInt(1);
            }
        }
        return 0;
    }

    public void upsert(String persona, UUID id, int points) throws SQLException {
        try (Connection c = ds.getConnection()) {
            try (PreparedStatement up = c.prepareStatement(
                    "UPDATE npcai_affinity SET points = ?, updated_at = ? WHERE persona = ? AND player_uuid = ?")) {
                up.setInt(1, points);
                up.setLong(2, System.currentTimeMillis());
                up.setString(3, persona);
                up.setString(4, id.toString());
                if (up.executeUpdate() == 0) {
                    try (PreparedStatement ins = c.prepareStatement(
                            "INSERT INTO npcai_affinity (points, updated_at, persona, player_uuid) VALUES (?, ?, ?, ?)")) {
                        ins.setInt(1, points);
                        ins.setLong(2, System.currentTimeMillis());
                        ins.setString(3, persona);
                        ins.setString(4, id.toString());
                        ins.executeUpdate();
                    }
                }
            }
        }
    }
}
