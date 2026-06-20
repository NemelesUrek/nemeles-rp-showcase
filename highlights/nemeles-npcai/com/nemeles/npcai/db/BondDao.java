package com.nemeles.npcai.db;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

/** Persistencia del easter egg de alma gemela: dueno UNICO por NPC + progreso por jugador. */
public final class BondDao {

    private final DataSource ds;

    public BondDao(DataSource ds) { this.ds = ds; }

    /** [owner_uuid, owner_name] o null si nadie lo tiene aun. */
    public String[] loadOwner(String persona) throws SQLException {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                "SELECT owner_uuid, owner_name FROM npcai_bond WHERE persona = ?")) {
            ps.setString(1, persona);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return new String[]{ rs.getString(1), rs.getString(2) };
            }
        }
        return null;
    }

    /** Reclama la propiedad si nadie la tiene. true si tras la llamada es de este jugador. */
    public boolean claimOwner(String persona, UUID id, String name) throws SQLException {
        try (Connection c = ds.getConnection()) {
            try (PreparedStatement sel = c.prepareStatement(
                    "SELECT owner_uuid FROM npcai_bond WHERE persona = ?")) {
                sel.setString(1, persona);
                try (ResultSet rs = sel.executeQuery()) {
                    if (rs.next()) return id.toString().equals(rs.getString(1));
                }
            }
            try (PreparedStatement ins = c.prepareStatement(
                    "INSERT INTO npcai_bond (persona, owner_uuid, owner_name, bonded_at) VALUES (?, ?, ?, ?)")) {
                ins.setString(1, persona);
                ins.setString(2, id.toString());
                ins.setString(3, name);
                ins.setLong(4, System.currentTimeMillis());
                ins.executeUpdate();
            }
        }
        return true;
    }

    public void clearOwner(String persona) throws SQLException {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement("DELETE FROM npcai_bond WHERE persona = ?")) {
            ps.setString(1, persona);
            ps.executeUpdate();
        }
    }

    /** [turns, phrase_idx, hinted]. */
    public int[] loadProgress(String persona, UUID id) throws SQLException {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                "SELECT turns, phrase_idx, hinted FROM npcai_bond_progress WHERE persona = ? AND player_uuid = ?")) {
            ps.setString(1, persona);
            ps.setString(2, id.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return new int[]{ rs.getInt(1), rs.getInt(2), rs.getInt(3) };
            }
        }
        return new int[]{0, 0, 0};
    }

    public void saveProgress(String persona, UUID id, int turns, int phraseIdx, int hinted) throws SQLException {
        try (Connection c = ds.getConnection()) {
            try (PreparedStatement up = c.prepareStatement(
                    "UPDATE npcai_bond_progress SET turns = ?, phrase_idx = ?, hinted = ? WHERE persona = ? AND player_uuid = ?")) {
                up.setInt(1, turns); up.setInt(2, phraseIdx); up.setInt(3, hinted);
                up.setString(4, persona); up.setString(5, id.toString());
                if (up.executeUpdate() == 0) {
                    try (PreparedStatement ins = c.prepareStatement(
                            "INSERT INTO npcai_bond_progress (turns, phrase_idx, hinted, persona, player_uuid) VALUES (?, ?, ?, ?, ?)")) {
                        ins.setInt(1, turns); ins.setInt(2, phraseIdx); ins.setInt(3, hinted);
                        ins.setString(4, persona); ins.setString(5, id.toString());
                        ins.executeUpdate();
                    }
                }
            }
        }
    }
}
