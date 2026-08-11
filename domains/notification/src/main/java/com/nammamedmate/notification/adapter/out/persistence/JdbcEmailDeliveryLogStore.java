package com.nammamedmate.notification.adapter.out.persistence;

import com.nammamedmate.notification.application.port.out.EmailDeliveryLogStore;
import com.nammamedmate.notification.domain.EmailBounceType;
import com.nammamedmate.notification.domain.EmailDeliveryLog;
import com.nammamedmate.notification.domain.EmailLogStatus;
import com.nammamedmate.notification.domain.EmailProvider;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class JdbcEmailDeliveryLogStore implements EmailDeliveryLogStore {

  private static final String SELECT =
      """
      SELECT id, to_email, to_name, template_id, subject, provider, fallback_used,
             provider_message_id, status, sent_at, delivered_at, opened_at, clicked_at,
             bounce_type, error_message
      FROM email_delivery_logs
      """;

  private final JdbcTemplate jdbc;

  public JdbcEmailDeliveryLogStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public void insert(EmailDeliveryLog log) {
    String providerName = null;
    if (log.provider() != null) {
      providerName = log.provider().name();
    }
    jdbc.update(
        """
        INSERT INTO email_delivery_logs (
          id, to_email, to_name, template_id, subject, provider, fallback_used,
          provider_message_id, status, sent_at, delivered_at, opened_at, clicked_at,
          bounce_type, error_message
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """,
        log.id(),
        log.toEmail(),
        log.toName(),
        log.templateId(),
        log.subject(),
        providerName,
        log.fallbackUsed(),
        log.providerMessageId(),
        log.status().name(),
        Timestamp.from(log.sentAt()),
        ts(log.deliveredAt()),
        ts(log.openedAt()),
        ts(log.clickedAt()),
        bounceName(log.bounceType()),
        log.errorMessage());
  }

  private static String bounceName(EmailBounceType bounceType) {
    if (bounceType == null) {
      return null;
    }
    return bounceType.name();
  }

  @Override
  public Optional<EmailDeliveryLog> findById(UUID id) {
    List<EmailDeliveryLog> rows = jdbc.query(SELECT + " WHERE id = ?", (rs, i) -> map(rs), id);
    return rows.stream().findFirst();
  }

  @Override
  public Optional<EmailDeliveryLog> findByProviderMessageId(String providerMessageId) {
    List<EmailDeliveryLog> rows =
        jdbc.query(
            SELECT + " WHERE provider_message_id = ?", (rs, i) -> map(rs), providerMessageId);
    return rows.stream().findFirst();
  }

  @Override
  public boolean markDelivered(String providerMessageId, Instant at) {
    int n =
        jdbc.update(
            """
            UPDATE email_delivery_logs
            SET status = CASE
                  WHEN status IN ('OPENED', 'CLICKED') THEN status
                  ELSE 'DELIVERED'
                END,
                delivered_at = COALESCE(delivered_at, ?)
            WHERE provider_message_id = ?
            """,
            Timestamp.from(at),
            providerMessageId);
    return n > 0;
  }

  @Override
  public boolean markOpened(UUID logId, Instant at) {
    int n =
        jdbc.update(
            """
            UPDATE email_delivery_logs
            SET status = CASE WHEN status = 'CLICKED' THEN status ELSE 'OPENED' END,
                opened_at = COALESCE(opened_at, ?)
            WHERE id = ?
            """,
            Timestamp.from(at),
            logId);
    return n > 0;
  }

  @Override
  public boolean markClicked(UUID logId, Instant at) {
    int n =
        jdbc.update(
            """
            UPDATE email_delivery_logs
            SET status = 'CLICKED',
                opened_at = COALESCE(opened_at, ?),
                clicked_at = COALESCE(clicked_at, ?)
            WHERE id = ?
            """,
            Timestamp.from(at),
            Timestamp.from(at),
            logId);
    return n > 0;
  }

  @Override
  public boolean markBounced(String providerMessageId, EmailBounceType bounceType, Instant at) {
    int n =
        jdbc.update(
            """
            UPDATE email_delivery_logs
            SET status = 'BOUNCED', bounce_type = ?
            WHERE provider_message_id = ?
            """,
            bounceType.name(),
            providerMessageId);
    return n > 0;
  }

  @Override
  public boolean markSpam(String providerMessageId, Instant at) {
    int n =
        jdbc.update(
            """
            UPDATE email_delivery_logs
            SET status = 'SPAM'
            WHERE provider_message_id = ?
            """,
            providerMessageId);
    return n > 0;
  }

  @Override
  public Page list(ListFilter filter) {
    StringBuilder where = new StringBuilder(" WHERE 1=1");
    List<Object> args = new ArrayList<>();
    if (filter.toEmail() != null) {
      where.append(" AND to_email = ?");
      args.add(filter.toEmail());
    }
    if (filter.templateId() != null) {
      where.append(" AND template_id = ?");
      args.add(filter.templateId());
    }
    if (filter.status() != null) {
      where.append(" AND status = ?");
      args.add(filter.status().name());
    }
    if (filter.dateFrom() != null) {
      where.append(" AND sent_at >= ?");
      args.add(Timestamp.from(filter.dateFrom()));
    }
    if (filter.dateTo() != null) {
      where.append(" AND sent_at <= ?");
      args.add(Timestamp.from(filter.dateTo()));
    }
    Integer total =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM email_delivery_logs" + where, Integer.class, args.toArray());
    long count = total == null ? 0L : total.longValue();
    int offset = (filter.page() - 1) * filter.limit();
    List<Object> pageArgs = new ArrayList<>(args);
    pageArgs.add(filter.limit());
    pageArgs.add(offset);
    List<EmailDeliveryLog> rows =
        jdbc.query(
            SELECT + where + " ORDER BY sent_at DESC LIMIT ? OFFSET ?",
            (rs, i) -> map(rs),
            pageArgs.toArray());
    return new Page(rows, count);
  }

  @Override
  public TemplateStats statsForTemplate(String templateId) {
    List<TemplateStats> rows =
        jdbc.query(
            """
            SELECT MAX(sent_at) AS last_sent,
                   COUNT(*) AS sent_count,
                   COUNT(opened_at) AS opened_count,
                   COUNT(clicked_at) AS clicked_count
            FROM email_delivery_logs
            WHERE template_id = ?
              AND status IN ('SENT', 'DELIVERED', 'OPENED', 'CLICKED')
            """,
            (rs, i) -> {
              Timestamp last = rs.getTimestamp("last_sent");
              return new TemplateStats(
                  last == null ? null : last.toInstant(),
                  rs.getLong("sent_count"),
                  rs.getLong("opened_count"),
                  rs.getLong("clicked_count"));
            },
            templateId);
    if (rows.isEmpty()) {
      return new TemplateStats(null, 0, 0, 0);
    }
    return rows.get(0);
  }

  private static EmailDeliveryLog map(ResultSet rs) throws SQLException {
    String provider = rs.getString("provider");
    EmailProvider emailProvider = null;
    if (provider != null) {
      emailProvider = EmailProvider.valueOf(provider);
    }
    String bounce = rs.getString("bounce_type");
    return new EmailDeliveryLog(
        (UUID) rs.getObject("id"),
        rs.getString("to_email"),
        rs.getString("to_name"),
        rs.getString("template_id"),
        rs.getString("subject"),
        emailProvider,
        rs.getBoolean("fallback_used"),
        rs.getString("provider_message_id"),
        EmailLogStatus.valueOf(rs.getString("status")),
        rs.getTimestamp("sent_at").toInstant(),
        instant(rs.getTimestamp("delivered_at")),
        instant(rs.getTimestamp("opened_at")),
        instant(rs.getTimestamp("clicked_at")),
        bounce == null ? null : EmailBounceType.valueOf(bounce),
        rs.getString("error_message"));
  }

  private static Instant instant(Timestamp ts) {
    if (ts == null) {
      return null;
    }
    return ts.toInstant();
  }

  private static Timestamp ts(Instant instant) {
    if (instant == null) {
      return null;
    }
    return Timestamp.from(instant);
  }
}
