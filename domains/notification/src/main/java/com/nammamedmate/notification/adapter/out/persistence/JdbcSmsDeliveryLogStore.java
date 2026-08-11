package com.nammamedmate.notification.adapter.out.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.notification.application.port.out.SmsDeliveryLogStore;
import com.nammamedmate.notification.domain.SmsDeliveryLog;
import com.nammamedmate.notification.domain.SmsLogStatus;
import com.nammamedmate.notification.domain.SmsProvider;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class JdbcSmsDeliveryLogStore implements SmsDeliveryLogStore {

  private static final String SELECT =
      """
      SELECT id, to_phone, template_id, variables, provider, provider_message_id,
             fallback_used, status, cost_rs, sent_at, delivered_at, error_message
      FROM sms_delivery_logs
      """;

  private final JdbcTemplate jdbc;
  private final ObjectMapper mapper;

  public JdbcSmsDeliveryLogStore(JdbcTemplate jdbc, ObjectMapper mapper) {
    this.jdbc = jdbc;
    this.mapper = mapper;
  }

  @Override
  public void insert(SmsDeliveryLog log) {
    jdbc.update(
        """
        INSERT INTO sms_delivery_logs (
          id, to_phone, template_id, variables, provider, provider_message_id,
          fallback_used, status, cost_rs, sent_at, delivered_at, error_message
        ) VALUES (?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?, ?, ?)
        """,
        log.id(),
        log.toPhone(),
        log.templateId(),
        toJson(log.variables()),
        log.provider() == null ? null : log.provider().name(),
        log.providerMessageId(),
        log.fallbackUsed(),
        log.status().name(),
        log.costRs(),
        Timestamp.from(log.sentAt()),
        ts(log.deliveredAt()),
        log.errorMessage());
  }

  @Override
  public Optional<SmsDeliveryLog> findById(UUID id) {
    List<SmsDeliveryLog> rows = jdbc.query(SELECT + " WHERE id = ?", (rs, i) -> map(rs), id);
    return rows.stream().findFirst();
  }

  @Override
  public Optional<SmsDeliveryLog> findByProviderMessageId(String providerMessageId) {
    List<SmsDeliveryLog> rows =
        jdbc.query(
            SELECT + " WHERE provider_message_id = ?", (rs, i) -> map(rs), providerMessageId);
    return rows.stream().findFirst();
  }

  @Override
  public boolean markDelivered(String providerMessageId, Instant deliveredAt) {
    int n =
        jdbc.update(
            """
            UPDATE sms_delivery_logs
            SET status = 'DELIVERED',
                delivered_at = COALESCE(delivered_at, ?)
            WHERE provider_message_id = ?
            """,
            Timestamp.from(deliveredAt),
            providerMessageId);
    return n > 0;
  }

  @Override
  public Page list(ListFilter filter) {
    StringBuilder where = new StringBuilder(" WHERE 1=1");
    List<Object> args = new ArrayList<>();
    if (filter.toPhone() != null) {
      where.append(" AND to_phone = ?");
      args.add(filter.toPhone());
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
            "SELECT COUNT(*) FROM sms_delivery_logs" + where, Integer.class, args.toArray());
    long count = total == null ? 0L : total.longValue();
    int offset = (filter.page() - 1) * filter.limit();
    List<Object> pageArgs = new ArrayList<>(args);
    pageArgs.add(filter.limit());
    pageArgs.add(offset);
    List<SmsDeliveryLog> rows =
        jdbc.query(
            SELECT + where + " ORDER BY sent_at DESC LIMIT ? OFFSET ?",
            (rs, i) -> map(rs),
            pageArgs.toArray());
    return new Page(rows, count);
  }

  @Override
  public BigDecimal sumCostBetween(Instant fromInclusive, Instant toExclusive) {
    BigDecimal sum =
        jdbc.queryForObject(
            """
            SELECT COALESCE(SUM(cost_rs), 0)
            FROM sms_delivery_logs
            WHERE sent_at >= ? AND sent_at < ?
              AND cost_rs IS NOT NULL
              AND status IN ('SENT', 'DELIVERED')
            """,
            BigDecimal.class,
            Timestamp.from(fromInclusive),
            Timestamp.from(toExclusive));
    return sum == null ? BigDecimal.ZERO : sum;
  }

  private SmsDeliveryLog map(ResultSet rs) throws SQLException {
    String provider = rs.getString("provider");
    Timestamp delivered = rs.getTimestamp("delivered_at");
    return new SmsDeliveryLog(
        (UUID) rs.getObject("id"),
        rs.getString("to_phone"),
        rs.getString("template_id"),
        parseVars(rs.getString("variables")),
        provider == null ? null : SmsProvider.valueOf(provider),
        rs.getString("provider_message_id"),
        rs.getBoolean("fallback_used"),
        SmsLogStatus.valueOf(rs.getString("status")),
        rs.getBigDecimal("cost_rs"),
        rs.getTimestamp("sent_at").toInstant(),
        delivered == null ? null : delivered.toInstant(),
        rs.getString("error_message"));
  }

  private String toJson(Map<String, String> variables) {
    try {
      return mapper.writeValueAsString(variables);
    } catch (JsonProcessingException e) {
      return "{}";
    }
  }

  private Map<String, String> parseVars(String json) {
    if (json == null || json.isBlank()) {
      return Map.of();
    }
    try {
      return mapper.readValue(json, new TypeReference<>() {});
    } catch (JsonProcessingException e) {
      return Map.of();
    }
  }

  private static Timestamp ts(Instant instant) {
    return instant == null ? null : Timestamp.from(instant);
  }
}
