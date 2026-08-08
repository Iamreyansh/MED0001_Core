package com.nammamedmate.auth.adapter.out.persistence;

import com.nammamedmate.auth.application.port.out.RiderAccountPort;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcRiderAccountAdapter implements RiderAccountPort {

  private final JdbcTemplate jdbc;

  public JdbcRiderAccountAdapter(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Optional<RiderAccount> findByPhone(String phone) {
    List<RiderAccount> rows =
        jdbc.query(
            """
            SELECT id, phone, name, status, kyc_status, email,
                   kyc_rejection_reason, kyc_rejection_notes
            FROM riders WHERE phone = ? AND deleted_at IS NULL
            """,
            (rs, i) -> mapRow(rs),
            phone);
    return rows.stream().findFirst();
  }

  @Override
  public Optional<RiderAccount> findById(UUID id) {
    List<RiderAccount> rows =
        jdbc.query(
            """
            SELECT id, phone, name, status, kyc_status, email,
                   kyc_rejection_reason, kyc_rejection_notes
            FROM riders WHERE id = ? AND deleted_at IS NULL
            """,
            (rs, i) -> mapRow(rs),
            id);
    return rows.stream().findFirst();
  }

  private static RiderAccount mapRow(java.sql.ResultSet rs) throws java.sql.SQLException {
    return new RiderAccount(
        (UUID) rs.getObject("id"),
        rs.getString("phone"),
        rs.getString("name"),
        rs.getString("status"),
        rs.getString("kyc_status"),
        rs.getString("email"),
        rs.getString("kyc_rejection_reason"),
        rs.getString("kyc_rejection_notes"));
  }
}
