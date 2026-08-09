package com.nammamedmate.integration.adapter.out.persistence;

import com.nammamedmate.integration.application.port.out.CommunicationCostDailyStore;
import com.nammamedmate.integration.domain.CommunicationCostDaily;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class JdbcCommunicationCostDailyStore implements CommunicationCostDailyStore {

  private final JdbcTemplate jdbc;

  public JdbcCommunicationCostDailyStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Optional<CommunicationCostDaily> find(LocalDate date, String channel, String provider) {
    List<CommunicationCostDaily> rows =
        jdbc.query(
            """
            SELECT * FROM communication_cost_daily
             WHERE date = ? AND channel = ? AND provider = ?
            """,
            this::mapRow,
            Date.valueOf(date),
            channel,
            provider);
    return rows.stream().findFirst();
  }

  @Override
  public List<CommunicationCostDaily> findByDate(LocalDate date) {
    return jdbc.query(
        "SELECT * FROM communication_cost_daily WHERE date = ?", this::mapRow, Date.valueOf(date));
  }

  @Override
  public List<CommunicationCostDaily> findByChannelAndDateRange(
      String channel, LocalDate fromInclusive, LocalDate toInclusive) {
    return jdbc.query(
        """
        SELECT * FROM communication_cost_daily
         WHERE channel = ? AND date >= ? AND date <= ?
         ORDER BY date
        """,
        this::mapRow,
        channel,
        Date.valueOf(fromInclusive),
        Date.valueOf(toInclusive));
  }

  @Override
  public void upsertIncrement(
      LocalDate date,
      String channel,
      String provider,
      int sentDelta,
      int deliveredDelta,
      int fallbackDelta,
      BigDecimal costDelta) {
    jdbc.update(
        """
        INSERT INTO communication_cost_daily (
          id, date, channel, provider, sent_count, delivered_count, fallback_sent_count, cost_rs, created_at
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, NOW())
        ON CONFLICT (date, channel, provider) DO UPDATE SET
          sent_count = communication_cost_daily.sent_count + EXCLUDED.sent_count,
          delivered_count = communication_cost_daily.delivered_count + EXCLUDED.delivered_count,
          fallback_sent_count = communication_cost_daily.fallback_sent_count + EXCLUDED.fallback_sent_count,
          cost_rs = communication_cost_daily.cost_rs + EXCLUDED.cost_rs
        """,
        UUID.randomUUID(),
        Date.valueOf(date),
        channel,
        provider,
        sentDelta,
        deliveredDelta,
        fallbackDelta,
        costDelta);
  }

  private CommunicationCostDaily mapRow(ResultSet rs, int rowNum) throws SQLException {
    return new CommunicationCostDaily(
        (UUID) rs.getObject("id"),
        rs.getDate("date").toLocalDate(),
        rs.getString("channel"),
        rs.getString("provider"),
        rs.getInt("sent_count"),
        rs.getInt("delivered_count"),
        rs.getInt("fallback_sent_count"),
        rs.getBigDecimal("cost_rs"),
        instant(rs.getTimestamp("created_at")));
  }

  private static Instant instant(java.sql.Timestamp ts) {
    return ts == null ? null : ts.toInstant();
  }
}
