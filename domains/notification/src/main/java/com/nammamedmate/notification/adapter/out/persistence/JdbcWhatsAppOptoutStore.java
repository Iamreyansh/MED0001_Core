package com.nammamedmate.notification.adapter.out.persistence;

import com.nammamedmate.notification.application.port.out.WhatsAppOptoutStore;
import com.nammamedmate.notification.domain.WhatsAppOptout;
import com.nammamedmate.notification.domain.WhatsAppOptoutSource;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class JdbcWhatsAppOptoutStore implements WhatsAppOptoutStore {

  private final JdbcTemplate jdbc;

  public JdbcWhatsAppOptoutStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public boolean isActivelyOptedOut(String phone) {
    Integer n =
        jdbc.queryForObject(
            """
            SELECT COUNT(*) FROM whatsapp_optouts
            WHERE phone = ? AND is_active = TRUE
            """,
            Integer.class,
            phone);
    return n != null && n > 0;
  }

  @Override
  public void upsertActive(UUID id, String phone, WhatsAppOptoutSource source, Instant at) {
    Integer existing =
        jdbc.queryForObject(
            """
            SELECT COUNT(*) FROM whatsapp_optouts
            WHERE phone = ? AND is_active = TRUE
            """,
            Integer.class,
            phone);
    if (existing != null && existing > 0) {
      jdbc.update(
          """
          UPDATE whatsapp_optouts
          SET optout_source = ?, opted_out_at = ?
          WHERE phone = ? AND is_active = TRUE
          """,
          source.name(),
          Timestamp.from(at),
          phone);
      return;
    }
    jdbc.update(
        """
        INSERT INTO whatsapp_optouts (id, phone, optout_source, opted_out_at, is_active)
        VALUES (?, ?, ?, ?, TRUE)
        """,
        id,
        phone,
        source.name(),
        Timestamp.from(at));
  }

  @Override
  public void deactivateByPhone(String phone) {
    jdbc.update(
        """
        UPDATE whatsapp_optouts
        SET is_active = FALSE
        WHERE phone = ? AND is_active = TRUE
        """,
        phone);
  }

  @Override
  public Optional<WhatsAppOptout> findActiveByPhone(String phone) {
    List<WhatsAppOptout> rows =
        jdbc.query(
            """
            SELECT id, phone, optout_source, opted_out_at, is_active
            FROM whatsapp_optouts
            WHERE phone = ? AND is_active = TRUE
            LIMIT 1
            """,
            (rs, i) ->
                new WhatsAppOptout(
                    (UUID) rs.getObject("id"),
                    rs.getString("phone"),
                    WhatsAppOptoutSource.valueOf(rs.getString("optout_source")),
                    rs.getTimestamp("opted_out_at").toInstant(),
                    rs.getBoolean("is_active")),
            phone);
    return rows.stream().findFirst();
  }
}
