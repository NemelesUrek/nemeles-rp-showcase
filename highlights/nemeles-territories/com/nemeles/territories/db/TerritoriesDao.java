package com.nemeles.territories.db;

import com.nemeles.core.api.territory.TerritoryService;
import com.nemeles.territories.TerritoryData;
import com.nemeles.territories.WarSession;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class TerritoriesDao {

    private final DataSource ds;

    public TerritoriesDao(DataSource ds) {
        this.ds = ds;
    }

    // ─── carga ───────────────────────────────────────────────
    public Map<Integer, TerritoryData> loadAll() throws SQLException {
        Map<Integer, TerritoryData> out = new HashMap<>();
        try (Connection c = ds.getConnection()) {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT id, name, world, tier, owner_faction, income_cents, state, captured_at, "
                            + "shield_until, last_income_at, last_upkeep_at, influence_units FROM territory_meta");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    TerritoryData t = new TerritoryData(rs.getInt(1), rs.getString(2), rs.getString(3),
                            rs.getInt(4), rs.getInt(5), rs.getLong(6), rs.getString(7), rs.getLong(8),
                            rs.getLong(9), rs.getLong(10), rs.getLong(11));
                    t.influence = rs.getLong(12);
                    out.put(t.id, t);
                }
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT territory_id, chunk_x, chunk_z FROM territory_chunk");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    TerritoryData t = out.get(rs.getInt(1));
                    if (t != null) t.chunks.add(TerritoryService.chunkKey(rs.getInt(2), rs.getInt(3)));
                }
            }
        }
        return out;
    }

    public int maxId() throws SQLException {
        try (Connection c = ds.getConnection();
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT MAX(id) FROM territory_meta")) {
            if (rs.next()) { int v = rs.getInt(1); return rs.wasNull() ? 0 : v; }
        }
        return 0;
    }

    public long maxHistoryId() throws SQLException {
        try (Connection c = ds.getConnection();
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT MAX(id) FROM territory_history")) {
            if (rs.next()) { long v = rs.getLong(1); return rs.wasNull() ? 0 : v; }
        }
        return 0;
    }

    // ─── escritura ───────────────────────────────────────────
    public void insertTerritory(TerritoryData t) throws SQLException {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO territory_meta (id, name, world, tier, owner_faction, income_cents, state, "
                             + "captured_at, shield_until, last_income_at, last_upkeep_at, influence_units) "
                             + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            ps.setInt(1, t.id);
            ps.setString(2, t.name);
            ps.setString(3, t.world);
            ps.setInt(4, t.tier);
            ps.setInt(5, t.ownerFaction);
            ps.setLong(6, t.incomeCents);
            ps.setString(7, t.state);
            ps.setLong(8, t.capturedAt);
            ps.setLong(9, t.shieldUntil);
            ps.setLong(10, t.lastIncomeAt);
            ps.setLong(11, t.lastUpkeepAt);
            ps.setLong(12, t.influence);
            ps.executeUpdate();
        }
    }

    public void updateMeta(TerritoryData t) throws SQLException {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE territory_meta SET name = ?, tier = ?, owner_faction = ?, income_cents = ?, "
                             + "state = ?, captured_at = ?, shield_until = ?, last_income_at = ?, "
                             + "last_upkeep_at = ?, influence_units = ? WHERE id = ?")) {
            ps.setString(1, t.name);
            ps.setInt(2, t.tier);
            ps.setInt(3, t.ownerFaction);
            ps.setLong(4, t.incomeCents);
            ps.setString(5, t.state);
            ps.setLong(6, t.capturedAt);
            ps.setLong(7, t.shieldUntil);
            ps.setLong(8, t.lastIncomeAt);
            ps.setLong(9, t.lastUpkeepAt);
            ps.setLong(10, t.influence);
            ps.setInt(11, t.id);
            ps.executeUpdate();
        }
    }

    public void deleteTerritory(int id) throws SQLException {
        try (Connection c = ds.getConnection()) {
            exec(c, "DELETE FROM territory_meta WHERE id = ?", id);
            exec(c, "DELETE FROM territory_chunk WHERE territory_id = ?", id);
        }
    }

    public void insertChunks(int territoryId, String world, Collection<Long> keys) throws SQLException {
        if (keys.isEmpty()) return;
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO territory_chunk (territory_id, world, chunk_x, chunk_z) VALUES (?, ?, ?, ?)")) {
            for (long k : keys) {
                ps.setInt(1, territoryId);
                ps.setString(2, world);
                ps.setInt(3, (int) k);            // 32 bits bajos = chunkX
                ps.setInt(4, (int) (k >> 32));    // 32 bits altos = chunkZ
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    public void insertHistory(long id, int territoryId, int from, int to, String reason, long at) throws SQLException {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO territory_history (id, territory_id, from_faction, to_faction, reason, at_ms) "
                             + "VALUES (?, ?, ?, ?, ?, ?)")) {
            ps.setLong(1, id);
            ps.setInt(2, territoryId);
            ps.setInt(3, from);
            ps.setInt(4, to);
            if (reason == null) ps.setNull(5, Types.VARCHAR); else ps.setString(5, reason);
            ps.setLong(6, at);
            ps.executeUpdate();
        }
    }

    private void exec(Connection c, String sql, int id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) { ps.setInt(1, id); ps.executeUpdate(); }
    }

    // ─── guerras (escrow + ventana) ──────────────────────────
    public long maxWarId() throws SQLException {
        try (Connection c = ds.getConnection();
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT MAX(id) FROM territory_war")) {
            if (rs.next()) { long v = rs.getLong(1); return rs.wasNull() ? 0 : v; }
        }
        return 0;
    }

    /** Guerras NO terminadas (state != 'ENDED'): para reconciliar al arrancar. */
    public List<WarSession> loadOpenWars() throws SQLException {
        List<WarSession> out = new ArrayList<>();
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT id, territory_id, attacker_faction, defender_faction, pot_cents, "
                             + "prep_end_ms, window_end_ms, state FROM territory_war WHERE state <> 'ENDED'");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.add(new WarSession(rs.getLong(1), rs.getInt(2), rs.getInt(3), rs.getInt(4),
                        rs.getLong(5), rs.getLong(6), rs.getLong(7), rs.getString(8)));
            }
        }
        return out;
    }

    public void insertWar(long id, int territoryId, int attacker, int defender, long potCents,
                          long prepEndMs, long windowEndMs, String state) throws SQLException {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO territory_war (id, territory_id, attacker_faction, defender_faction, "
                             + "pot_cents, prep_end_ms, window_end_ms, state) VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
            ps.setLong(1, id);
            ps.setInt(2, territoryId);
            ps.setInt(3, attacker);
            ps.setInt(4, defender);
            ps.setLong(5, potCents);
            ps.setLong(6, prepEndMs);
            ps.setLong(7, windowEndMs);
            ps.setString(8, state);
            ps.executeUpdate();
        }
    }

    public void updateWarState(long id, String state) throws SQLException {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE territory_war SET state = ? WHERE id = ?")) {
            ps.setString(1, state);
            ps.setLong(2, id);
            ps.executeUpdate();
        }
    }
}
