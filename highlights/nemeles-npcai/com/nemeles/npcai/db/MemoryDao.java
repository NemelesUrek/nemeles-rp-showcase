package com.nemeles.npcai.db;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

/** Acceso a la memoria de largo plazo de los NPCs: notas breves que cada persona recuerda de cada jugador. */
public final class MemoryDao {

    private final DataSource ds;

    public MemoryDao(DataSource ds) { this.ds = ds; }

    /** Devuelve las notas guardadas (o "" si no hay). */
    public String load(String persona, UUID id) throws SQLException {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT memory FROM npcai_memory WHERE persona = ? AND player_uuid = ?")) {
            ps.setString(1, persona);
            ps.setString(2, id.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String m = rs.getString(1);
                    return m == null ? "" : m;
                }
            }
        }
        return "";
    }

    public void upsert(String persona, UUID id, String memory) throws SQLException {
        try (Connection c = ds.getConnection()) {
            try (PreparedStatement up = c.prepareStatement(
                    "UPDATE npcai_memory SET memory = ?, updated_at = ? WHERE persona = ? AND player_uuid = ?")) {
                up.setString(1, memory);
                up.setLong(2, System.currentTimeMillis());
                up.setString(3, persona);
                up.setString(4, id.toString());
                if (up.executeUpdate() == 0) {
                    try (PreparedStatement ins = c.prepareStatement(
                            "INSERT INTO npcai_memory (memory, updated_at, persona, player_uuid) VALUES (?, ?, ?, ?)")) {
                        ins.setString(1, memory);
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
