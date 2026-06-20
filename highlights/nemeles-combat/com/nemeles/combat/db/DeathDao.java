package com.nemeles.combat.db;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

public final class DeathDao {

    private final DataSource ds;
    private final AtomicLong nextId = new AtomicLong(0);

    public DeathDao(DataSource ds) {
        this.ds = ds;
    }

    public void init() throws SQLException {
        try (Connection c = ds.getConnection();
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT MAX(id) FROM combat_death_log")) {
            if (rs.next()) { long v = rs.getLong(1); nextId.set(rs.wasNull() ? 0 : v); }
        }
    }

    public void insert(UUID victim, UUID killer, String cause, boolean perma, long dirtyLostCents,
                       String world, int x, int y, int z, long at) throws SQLException {
        long id = nextId.incrementAndGet();
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO combat_death_log (id, victim, killer, cause, permadeath, dirty_lost_cents, "
                             + "world, x, y, z, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            ps.setLong(1, id);
            ps.setString(2, victim.toString());
            if (killer == null) ps.setNull(3, Types.VARCHAR); else ps.setString(3, killer.toString());
            ps.setString(4, cause);
            ps.setInt(5, perma ? 1 : 0);
            ps.setLong(6, dirtyLostCents);
            if (world == null) ps.setNull(7, Types.VARCHAR); else ps.setString(7, world);
            ps.setInt(8, x);
            ps.setInt(9, y);
            ps.setInt(10, z);
            ps.setLong(11, at);
            ps.executeUpdate();
        }
    }
}
