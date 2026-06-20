package com.nemeles.jobs.db;

import com.nemeles.jobs.JobProgress;
import com.nemeles.jobs.PlayerJobs;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Acceso a jobs_levels (habilidades). Todas las llamadas corren en el dbExecutor del core. */
public final class JobsDao {

    private final DataSource ds;

    public JobsDao(DataSource ds) {
        this.ds = ds;
    }

    public PlayerJobs load(UUID uuid) throws SQLException {
        PlayerJobs pj = new PlayerJobs();
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT job, level, xp, total_xp, last_used, peak_level FROM jobs_levels WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    int lvl = rs.getInt(2);
                    int peak = Math.max(rs.getInt(6), lvl); // backfill: peak >= nivel actual (datos previos a la decadencia)
                    pj.put(rs.getString(1), new JobProgress(lvl, rs.getLong(3), rs.getLong(4), rs.getLong(5), peak));
                }
            }
        }
        return pj;
    }

    public void upsert(UUID uuid, String job, JobProgress p) throws SQLException {
        try (Connection c = ds.getConnection()) {
            try (PreparedStatement up = c.prepareStatement(
                    "UPDATE jobs_levels SET level = ?, xp = ?, total_xp = ?, last_used = ?, peak_level = ? "
                            + "WHERE uuid = ? AND job = ?")) {
                up.setInt(1, p.level());
                up.setLong(2, p.xp());
                up.setLong(3, p.totalXp());
                up.setLong(4, p.lastUsed());
                up.setInt(5, p.peakLevel());
                up.setString(6, uuid.toString());
                up.setString(7, job);
                if (up.executeUpdate() == 0) {
                    try (PreparedStatement ins = c.prepareStatement(
                            "INSERT INTO jobs_levels (uuid, job, level, xp, total_xp, joined_at, last_used, peak_level) "
                                    + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
                        long now = System.currentTimeMillis();
                        ins.setString(1, uuid.toString());
                        ins.setString(2, job);
                        ins.setInt(3, p.level());
                        ins.setLong(4, p.xp());
                        ins.setLong(5, p.totalXp());
                        ins.setLong(6, now);
                        ins.setLong(7, p.lastUsed() > 0 ? p.lastUsed() : now);
                        ins.setInt(8, p.peakLevel());
                        ins.executeUpdate();
                    }
                }
            }
        }
    }

    /** Top por XP total: filas {uuid, level, totalXp}. */
    public List<String[]> top(String job, int limit) throws SQLException {
        List<String[]> out = new ArrayList<>();
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT uuid, level, total_xp FROM jobs_levels WHERE job = ? ORDER BY total_xp DESC LIMIT ?")) {
            ps.setString(1, job);
            ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new String[]{rs.getString(1), String.valueOf(rs.getInt(2)), String.valueOf(rs.getLong(3))});
                }
            }
        }
        return out;
    }
}
