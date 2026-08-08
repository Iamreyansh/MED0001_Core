package com.nammamedmate.rider.adapter.out.persistence;

import com.nammamedmate.rider.application.port.out.RiderSessionRevokePort;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcRiderSessionRevokeAdapter implements RiderSessionRevokePort {

  private final JdbcTemplate jdbc;

  public JdbcRiderSessionRevokeAdapter(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public int revokeAllForUser(UUID userId, Instant revokedAt) {
    Integer n =
        jdbc.update(
            """
            UPDATE auth_sessions
            SET revoked_at = ?
            WHERE user_id = ? AND revoked_at IS NULL
            """,
            java.sql.Timestamp.from(revokedAt),
            userId);
    return n;
  }
}
