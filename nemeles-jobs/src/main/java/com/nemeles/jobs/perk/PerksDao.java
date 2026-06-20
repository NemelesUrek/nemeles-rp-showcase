package com.nemeles.jobs.perk;

import com.nemeles.jobs.PlayerJobs;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

public final class PerksDao {

    private final DataSource ds;

    public PerksDao(DataSource ds) {
        this.ds = ds;
    }

    public void loadInto(UUID uuid, PlayerJobs pj) throws SQLException {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT skill, tier, choice FROM jobs_perks WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String ch = rs.getString(3);
                    if (ch != null && !ch.isEmpty()) pj.putPerk(rs.getString(1), rs.getInt(2), ch.charAt(0));
                }
            }
        }
    }

    public void save(UUID uuid, String skill, int tier, char choice) throws SQLException {
        try (Connection c = ds.getConnection()) {
            try (PreparedStatement up = c.prepareStatement(
                    "UPDATE jobs_perks SET choice = ?, chosen_at = ? WHERE uuid = ? AND skill = ? AND tier = ?")) {
                up.setString(1, String.valueOf(choice));
                up.setLong(2, System.currentTimeMillis());
                up.setString(3, uuid.toString());
                up.setString(4, skill);
                up.setInt(5, tier);
                if (up.executeUpdate() == 0) {
                    try (PreparedStatement ins = c.prepareStatement(
                            "INSERT INTO jobs_perks (uuid, skill, tier, choice, chosen_at) VALUES (?, ?, ?, ?, ?)")) {
                        ins.setString(1, uuid.toString());
                        ins.setString(2, skill);
                        ins.setInt(3, tier);
                        ins.setString(4, String.valueOf(choice));
                        ins.setLong(5, System.currentTimeMillis());
                        ins.executeUpdate();
                    }
                }
            }
        }
    }
}
