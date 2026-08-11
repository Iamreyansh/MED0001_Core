package com.nammamedmate.notification.adapter.out.persistence;

import com.nammamedmate.notification.application.port.out.WhatsAppSessionStore;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class JdbcWhatsAppSessionStore implements WhatsAppSessionStore {

  private final JdbcTemplate jdbc;

  public JdbcWhatsAppSessionStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public void upsertCustomerMessage(String phone, Instant at) {
    int updated =
        jdbc.update(
            """
            UPDATE whatsapp_sessions
            SET last_customer_message_at = ?
            WHERE phone = ?
            """,
            Timestamp.from(at),
            phone);
    if (updated == 0) {
      jdbc.update(
          """
          INSERT INTO whatsapp_sessions (phone, last_customer_message_at)
          VALUES (?, ?)
          """,
          phone,
          Timestamp.from(at));
    }
  }

  @Override
  public Optional<Instant> lastCustomerMessageAt(String phone) {
    List<Instant> rows =
        jdbc.query(
            """
            SELECT last_customer_message_at FROM whatsapp_sessions WHERE phone = ?
            """,
            (rs, i) -> rs.getTimestamp("last_customer_message_at").toInstant(),
            phone);
    return rows.stream().findFirst();
  }
}
