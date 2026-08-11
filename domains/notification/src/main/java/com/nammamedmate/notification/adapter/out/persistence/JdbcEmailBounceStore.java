package com.nammamedmate.notification.adapter.out.persistence;

import com.nammamedmate.notification.application.port.out.EmailBounceStore;
import com.nammamedmate.notification.domain.EmailBounce;
import com.nammamedmate.notification.domain.EmailBounceType;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class JdbcEmailBounceStore implements EmailBounceStore {

  private final JdbcTemplate jdbc;

  public JdbcEmailBounceStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public void insert(EmailBounce bounce) {
    jdbc.update(
        """
        INSERT INTO email_bounces (
          id, email, bounce_type, bounce_reason, is_unsubscribed, recorded_at
        ) VALUES (?, ?, ?, ?, ?, ?)
        """,
        bounce.id(),
        bounce.email(),
        bounce.bounceType().name(),
        bounce.bounceReason(),
        bounce.unsubscribed(),
        Timestamp.from(bounce.recordedAt()));
  }

  @Override
  public boolean hasHardBounce(String email) {
    Integer n =
        jdbc.queryForObject(
            """
            SELECT COUNT(*) FROM email_bounces
            WHERE email = ? AND bounce_type = 'HARD'
            """,
            Integer.class,
            email);
    return n != null && n > 0;
  }

  @Override
  public Optional<EmailBounce> findLatestHard(String email) {
    List<EmailBounce> rows =
        jdbc.query(
            """
            SELECT id, email, bounce_type, bounce_reason, is_unsubscribed, recorded_at
            FROM email_bounces
            WHERE email = ? AND bounce_type = 'HARD'
            ORDER BY recorded_at DESC
            LIMIT 1
            """,
            (rs, i) -> map(rs),
            email);
    return rows.stream().findFirst();
  }

  private static EmailBounce map(ResultSet rs) throws SQLException {
    return new EmailBounce(
        (UUID) rs.getObject("id"),
        rs.getString("email"),
        EmailBounceType.valueOf(rs.getString("bounce_type")),
        rs.getString("bounce_reason"),
        rs.getBoolean("is_unsubscribed"),
        rs.getTimestamp("recorded_at").toInstant());
  }
}
