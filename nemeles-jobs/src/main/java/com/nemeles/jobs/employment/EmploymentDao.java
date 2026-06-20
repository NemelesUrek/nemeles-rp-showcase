package com.nemeles.jobs.employment;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.UUID;

/** Acceso a player_job. Corre en el dbExecutor del core. */
public final class EmploymentDao {

    private final DataSource ds;

    public EmploymentDao(DataSource ds) {
        this.ds = ds;
    }

    public EmploymentRecord load(UUID uuid) throws SQLException {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT job, last_change FROM player_job WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return new EmploymentRecord(rs.getString(1), rs.getLong(2));
                }
            }
        }
        return null;
    }

    public void upsert(UUID uuid, EmploymentRecord rec) throws SQLException {
        try (Connection c = ds.getConnection()) {
            try (PreparedStatement up = c.prepareStatement(
                    "UPDATE player_job SET job = ?, last_change = ? WHERE uuid = ?")) {
                if (rec.job == null) up.setNull(1, Types.VARCHAR); else up.setString(1, rec.job);
                up.setLong(2, rec.lastChange);
                up.setString(3, uuid.toString());
                if (up.executeUpdate() == 0) {
                    try (PreparedStatement ins = c.prepareStatement(
                            "INSERT INTO player_job (uuid, job, last_change) VALUES (?, ?, ?)")) {
                        ins.setString(1, uuid.toString());
                        if (rec.job == null) ins.setNull(2, Types.VARCHAR); else ins.setString(2, rec.job);
                        ins.setLong(3, rec.lastChange);
                        ins.executeUpdate();
                    }
                }
            }
        }
    }
}
