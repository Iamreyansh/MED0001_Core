package com.nammamedmate.notification.adapter.out.persistence;

import com.nammamedmate.notification.application.port.out.DispatchLogStore;
import com.nammamedmate.notification.domain.DispatchLogEntry;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class JdbcDispatchLogStore implements DispatchLogStore {

  private static final String SELECT =
      """
      SELECT dispatch_id, recipient_id, recipient_type, channel, type, title,
             status, sent_at, delivered_at
      FROM notification_dispatch_log
      """;

  private final JdbcTemplate jdbc;

  public JdbcDispatchLogStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Page list(ListFilter filter) {
    StringBuilder where = new StringBuilder(" WHERE 1=1");
    List<Object> args = new ArrayList<>();
    if (filter.channel() != null) {
      where.append(" AND channel = ?");
      args.add(filter.channel());
    }
    if (filter.status() != null) {
      where.append(" AND status = ?");
      args.add(filter.status());
    }
    if (filter.recipientType() != null) {
      where.append(" AND recipient_type = ?");
      args.add(filter.recipientType());
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
            "SELECT COUNT(*) FROM notification_dispatch_log" + where,
            Integer.class,
            args.toArray());
    long count = total == null ? 0L : total.longValue();
    int offset = (filter.page() - 1) * filter.limit();
    List<Object> pageArgs = new ArrayList<>(args);
    pageArgs.add(filter.limit());
    pageArgs.add(offset);
    List<DispatchLogEntry> rows =
        jdbc.query(
            SELECT + where + " ORDER BY sent_at DESC LIMIT ? OFFSET ?",
            (rs, i) -> map(rs),
            pageArgs.toArray());
    return new Page(rows, count);
  }

  private static DispatchLogEntry map(ResultSet rs) throws SQLException {
    Timestamp delivered = rs.getTimestamp("delivered_at");
    return new DispatchLogEntry(
        (UUID) rs.getObject("dispatch_id"),
        (UUID) rs.getObject("recipient_id"),
        rs.getString("recipient_type"),
        rs.getString("channel"),
        rs.getString("type"),
        rs.getString("title"),
        rs.getString("status"),
        rs.getTimestamp("sent_at").toInstant(),
        delivered == null ? null : delivered.toInstant());
  }
}
