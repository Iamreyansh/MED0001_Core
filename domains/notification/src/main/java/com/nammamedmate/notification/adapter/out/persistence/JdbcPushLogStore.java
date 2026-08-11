package com.nammamedmate.notification.adapter.out.persistence;

import com.nammamedmate.notification.application.port.out.PushLogStore;
import com.nammamedmate.notification.domain.NotificationUserType;
import com.nammamedmate.notification.domain.PushLogStatus;
import com.nammamedmate.notification.domain.PushNotificationLog;
import com.nammamedmate.notification.domain.PushPriority;
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
public class JdbcPushLogStore implements PushLogStore {

  private static final String SELECT =
      """
      SELECT id, broadcast_id, recipient_user_id, recipient_type, device_token_id,
             title, body, priority, fcm_message_id, status, sent_at, delivered_at,
             opened_at, error_message
      FROM push_notification_logs
      """;

  private final JdbcTemplate jdbc;

  public JdbcPushLogStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public void insert(PushNotificationLog log) {
    jdbc.update(
        """
        INSERT INTO push_notification_logs (
          id, broadcast_id, recipient_user_id, recipient_type, device_token_id,
          title, body, priority, fcm_message_id, status, sent_at, delivered_at,
          opened_at, error_message
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """,
        log.id(),
        log.broadcastId(),
        log.recipientUserId(),
        log.recipientType().name(),
        log.deviceTokenId(),
        log.title(),
        log.body(),
        log.priority().name(),
        log.fcmMessageId(),
        log.status().name(),
        Timestamp.from(log.sentAt()),
        ts(log.deliveredAt()),
        ts(log.openedAt()),
        log.errorMessage());
  }

  @Override
  public Optional<PushNotificationLog> findById(UUID id) {
    List<PushNotificationLog> rows = jdbc.query(SELECT + " WHERE id = ?", (rs, i) -> map(rs), id);
    return rows.stream().findFirst();
  }

  @Override
  public boolean markOpened(UUID logId, UUID recipientUserId, Instant openedAt) {
    int n =
        jdbc.update(
            """
            UPDATE push_notification_logs
            SET opened_at = COALESCE(opened_at, ?)
            WHERE id = ? AND recipient_user_id = ?
            """,
            Timestamp.from(openedAt),
            logId,
            recipientUserId);
    return n > 0;
  }

  @Override
  public Page list(ListFilter filter) {
    StringBuilder where = new StringBuilder(" WHERE 1=1");
    List<Object> args = new ArrayList<>();
    if (filter.recipientType() != null) {
      where.append(" AND recipient_type = ?");
      args.add(filter.recipientType().name());
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
            "SELECT COUNT(*) FROM push_notification_logs" + where, Integer.class, args.toArray());
    long count = total == null ? 0L : total.longValue();
    int offset = (filter.page() - 1) * filter.limit();
    List<Object> pageArgs = new ArrayList<>(args);
    pageArgs.add(filter.limit());
    pageArgs.add(offset);
    List<PushNotificationLog> rows =
        jdbc.query(
            SELECT + where + " ORDER BY sent_at DESC LIMIT ? OFFSET ?",
            (rs, i) -> map(rs),
            pageArgs.toArray());
    return new Page(rows, count);
  }

  private static PushNotificationLog map(ResultSet rs) throws SQLException {
    Timestamp delivered = rs.getTimestamp("delivered_at");
    Timestamp opened = rs.getTimestamp("opened_at");
    return new PushNotificationLog(
        (UUID) rs.getObject("id"),
        (UUID) rs.getObject("broadcast_id"),
        (UUID) rs.getObject("recipient_user_id"),
        NotificationUserType.valueOf(rs.getString("recipient_type")),
        (UUID) rs.getObject("device_token_id"),
        rs.getString("title"),
        rs.getString("body"),
        PushPriority.valueOf(rs.getString("priority")),
        rs.getString("fcm_message_id"),
        PushLogStatus.valueOf(rs.getString("status")),
        rs.getTimestamp("sent_at").toInstant(),
        delivered == null ? null : delivered.toInstant(),
        opened == null ? null : opened.toInstant(),
        rs.getString("error_message"));
  }

  private static Timestamp ts(Instant instant) {
    return instant == null ? null : Timestamp.from(instant);
  }
}
