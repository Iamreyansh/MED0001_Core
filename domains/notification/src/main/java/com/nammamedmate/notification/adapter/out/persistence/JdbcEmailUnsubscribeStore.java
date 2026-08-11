package com.nammamedmate.notification.adapter.out.persistence;

import com.nammamedmate.notification.application.port.out.EmailUnsubscribeStore;
import com.nammamedmate.notification.domain.EmailUnsubscribe;
import com.nammamedmate.notification.domain.EmailUnsubscribeSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class JdbcEmailUnsubscribeStore implements EmailUnsubscribeStore {

  private final JdbcTemplate jdbc;

  public JdbcEmailUnsubscribeStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public void upsertActive(UUID id, String email, EmailUnsubscribeSource source, Instant at) {
    Integer existing =
        jdbc.queryForObject(
            """
            SELECT COUNT(*) FROM email_unsubscribes
            WHERE email = ? AND is_active = TRUE
            """,
            Integer.class,
            email);
    if (existing != null && existing > 0) {
      jdbc.update(
          """
          UPDATE email_unsubscribes
          SET unsubscribe_source = ?, unsubscribed_at = ?
          WHERE email = ? AND is_active = TRUE
          """,
          source.name(),
          Timestamp.from(at),
          email);
      return;
    }
    jdbc.update(
        """
        INSERT INTO email_unsubscribes (
          id, email, unsubscribe_source, unsubscribed_at, is_active
        ) VALUES (?, ?, ?, ?, TRUE)
        """,
        id,
        email,
        source.name(),
        Timestamp.from(at));
  }

  @Override
  public boolean isActivelyUnsubscribed(String email) {
    Integer n =
        jdbc.queryForObject(
            """
            SELECT COUNT(*) FROM email_unsubscribes
            WHERE email = ? AND is_active = TRUE
            """,
            Integer.class,
            email);
    return n != null && n > 0;
  }

  @Override
  public Optional<EmailUnsubscribe> findActive(String email) {
    List<EmailUnsubscribe> rows =
        jdbc.query(
            """
            SELECT id, email, unsubscribe_source, unsubscribed_at, is_active
            FROM email_unsubscribes
            WHERE email = ? AND is_active = TRUE
            ORDER BY unsubscribed_at DESC
            LIMIT 1
            """,
            (rs, i) -> map(rs),
            email);
    return rows.stream().findFirst();
  }

  private static EmailUnsubscribe map(ResultSet rs) throws SQLException {
    return new EmailUnsubscribe(
        (UUID) rs.getObject("id"),
        rs.getString("email"),
        EmailUnsubscribeSource.valueOf(rs.getString("unsubscribe_source")),
        rs.getTimestamp("unsubscribed_at").toInstant(),
        rs.getBoolean("is_active"));
  }
}
