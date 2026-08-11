package com.nammamedmate.notification.adapter.out.persistence;

import com.nammamedmate.notification.application.port.out.InAppNotificationStore;
import com.nammamedmate.notification.domain.InAppNotification;
import com.nammamedmate.notification.domain.InAppNotificationType;
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
public class JdbcInAppNotificationStore implements InAppNotificationStore {

  private static final String SELECT =
      """
      SELECT id, customer_id, type, title, body, action_url, is_read, is_deleted,
             read_at, expires_at, created_at
      FROM customer_in_app_notifications
      """;

  private final JdbcTemplate jdbc;

  public JdbcInAppNotificationStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public void insert(InAppNotification n) {
    jdbc.update(
        """
        INSERT INTO customer_in_app_notifications (
          id, customer_id, type, title, body, action_url, is_read, is_deleted,
          read_at, expires_at, created_at
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """,
        n.id(),
        n.customerId(),
        n.type().name(),
        n.title(),
        n.body(),
        n.actionUrl(),
        n.read(),
        n.deleted(),
        ts(n.readAt()),
        Timestamp.from(n.expiresAt()),
        Timestamp.from(n.createdAt()));
  }

  @Override
  public Optional<InAppNotification> findByIdForCustomer(UUID id, UUID customerId) {
    List<InAppNotification> rows =
        jdbc.query(
            SELECT + " WHERE id = ? AND customer_id = ? AND is_deleted = FALSE",
            (rs, i) -> map(rs),
            id,
            customerId);
    return rows.stream().findFirst();
  }

  @Override
  public Page list(ListFilter filter) {
    StringBuilder where =
        new StringBuilder(" WHERE customer_id = ? AND is_deleted = FALSE AND expires_at > ?");
    List<Object> args = new ArrayList<>();
    args.add(filter.customerId());
    args.add(Timestamp.from(filter.now()));
    if (filter.unreadOnly()) {
      where.append(" AND is_read = FALSE");
    }
    if (filter.type() != null) {
      where.append(" AND type = ?");
      args.add(filter.type().name());
    }
    Integer total =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM customer_in_app_notifications" + where,
            Integer.class,
            args.toArray());
    long count = total == null ? 0L : total.longValue();
    int offset = (filter.page() - 1) * filter.limit();
    List<Object> pageArgs = new ArrayList<>(args);
    pageArgs.add(filter.limit());
    pageArgs.add(offset);
    List<InAppNotification> rows =
        jdbc.query(
            SELECT + where + " ORDER BY created_at DESC LIMIT ? OFFSET ?",
            (rs, i) -> map(rs),
            pageArgs.toArray());
    return new Page(rows, count);
  }

  @Override
  public long countUnread(UUID customerId, Instant now) {
    Integer n =
        jdbc.queryForObject(
            """
            SELECT COUNT(*) FROM customer_in_app_notifications
            WHERE customer_id = ? AND is_deleted = FALSE AND is_read = FALSE
              AND expires_at > ?
            """,
            Integer.class,
            customerId,
            Timestamp.from(now));
    return n == null ? 0L : n.longValue();
  }

  @Override
  public boolean markRead(UUID id, UUID customerId, Instant readAt) {
    int n =
        jdbc.update(
            """
            UPDATE customer_in_app_notifications
            SET is_read = TRUE, read_at = COALESCE(read_at, ?)
            WHERE id = ? AND customer_id = ? AND is_deleted = FALSE AND expires_at > ?
            """,
            Timestamp.from(readAt),
            id,
            customerId,
            Timestamp.from(readAt));
    return n > 0;
  }

  @Override
  public int markAllRead(UUID customerId, Instant readAt, Instant now) {
    return jdbc.update(
        """
        UPDATE customer_in_app_notifications
        SET is_read = TRUE, read_at = COALESCE(read_at, ?)
        WHERE customer_id = ? AND is_deleted = FALSE AND is_read = FALSE
          AND expires_at > ?
        """,
        Timestamp.from(readAt),
        customerId,
        Timestamp.from(now));
  }

  @Override
  public boolean softDelete(UUID id, UUID customerId) {
    int n =
        jdbc.update(
            """
            UPDATE customer_in_app_notifications
            SET is_deleted = TRUE
            WHERE id = ? AND customer_id = ? AND is_deleted = FALSE
            """,
            id,
            customerId);
    return n > 0;
  }

  @Override
  public int softDeleteExpired(Instant now) {
    return jdbc.update(
        """
        UPDATE customer_in_app_notifications
        SET is_deleted = TRUE
        WHERE is_deleted = FALSE AND expires_at <= ?
        """,
        Timestamp.from(now));
  }

  @Override
  public int hardDeletePastRetention(Instant cutoff) {
    return jdbc.update(
        """
        DELETE FROM customer_in_app_notifications
        WHERE is_deleted = TRUE AND expires_at <= ?
        """,
        Timestamp.from(cutoff));
  }

  private static InAppNotification map(ResultSet rs) throws SQLException {
    Timestamp readAt = rs.getTimestamp("read_at");
    return new InAppNotification(
        (UUID) rs.getObject("id"),
        (UUID) rs.getObject("customer_id"),
        InAppNotificationType.valueOf(rs.getString("type")),
        rs.getString("title"),
        rs.getString("body"),
        rs.getString("action_url"),
        rs.getBoolean("is_read"),
        rs.getBoolean("is_deleted"),
        readAt == null ? null : readAt.toInstant(),
        rs.getTimestamp("expires_at").toInstant(),
        rs.getTimestamp("created_at").toInstant());
  }

  private static Timestamp ts(Instant instant) {
    return instant == null ? null : Timestamp.from(instant);
  }
}
