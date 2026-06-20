package com.nemeles.jobs.weed;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/** Acceso a jobs_plants. Corre en el dbExecutor del core. */
public final class WeedDao {

    private final DataSource ds;

    public WeedDao(DataSource ds) {
        this.ds = ds;
    }

    public void insert(WeedPlant p) throws SQLException {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO jobs_plants (id, owner, world, x, y, z, planted_at, stage, state) "
                             + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            ps.setString(1, p.id);
            ps.setString(2, p.owner);
            ps.setString(3, p.world);
            ps.setInt(4, p.x);
            ps.setInt(5, p.y);
            ps.setInt(6, p.z);
            ps.setLong(7, p.plantedAt);
            ps.setInt(8, p.stage);
            ps.setString(9, p.state);
            ps.executeUpdate();
        }
    }

    public void updateState(String id, int stage, String state) throws SQLException {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE jobs_plants SET stage = ?, state = ? WHERE id = ?")) {
            ps.setInt(1, stage);
            ps.setString(2, state);
            ps.setString(3, id);
            ps.executeUpdate();
        }
    }

    public void delete(String id) throws SQLException {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement("DELETE FROM jobs_plants WHERE id = ?")) {
            ps.setString(1, id);
            ps.executeUpdate();
        }
    }

    public List<WeedPlant> loadActive() throws SQLException {
        List<WeedPlant> out = new ArrayList<>();
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT id, owner, world, x, y, z, planted_at, stage, state, cared, quality FROM jobs_plants WHERE state <> 'DEAD'");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                WeedPlant p = new WeedPlant(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getInt(4), rs.getInt(5), rs.getInt(6), rs.getLong(7), rs.getInt(8), rs.getString(9));
                p.cared = rs.getInt(10) == 1;
                p.quality = rs.getInt(11);
                out.add(p);
            }
        }
        return out;
    }

    /** Guarda el resultado del fertilizante (cuidada + calidad del minijuego). */
    public void updateCare(String id, boolean cared, int quality) throws SQLException {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE jobs_plants SET cared = ?, quality = ? WHERE id = ?")) {
            ps.setInt(1, cared ? 1 : 0);
            ps.setInt(2, quality);
            ps.setString(3, id);
            ps.executeUpdate();
        }
    }
}
