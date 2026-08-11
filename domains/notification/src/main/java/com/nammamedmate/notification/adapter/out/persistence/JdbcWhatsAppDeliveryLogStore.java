package com.nammamedmate.notification.adapter.out.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.notification.application.port.out.WhatsAppDeliveryLogStore;
import com.nammamedmate.notification.domain.WhatsAppDeliveryLog;
import com.nammamedmate.notification.domain.WhatsAppLogStatus;
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
public class JdbcWhatsAppDeliveryLogStore implements WhatsAppDeliveryLogStore {

  private static final String SELECT =
      """
      SELECT id, to_phone, template_name, components_json, wa_message_id, status,
             cost_rs, sent_at, delivered_at, read_at, error_code, error_message
      FROM whatsapp_delivery_logs
      """;

  private final JdbcTemplate jdbc;
  private final ObjectMapper mapper;

  public JdbcWhatsAppDeliveryLogStore(JdbcTemplate jdbc, ObjectMapper mapper) {
    this.jdbc = jdbc;
    this.mapper = mapper;
  }

  @Override
  public void insert(WhatsAppDeliveryLog log) {
    jdbc.update(
        """
        INSERT INTO whatsapp_delivery_logs (
          id, to_phone, template_name, components_json, wa_message_id, status,
          cost_rs, sent_at, delivered_at, read_at, error_code, error_message
        ) VALUES (?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?, ?, ?)
        """,
        log.id(),
        log.toPhone(),
        log.templateName(),
        toJson(log.components()),
        log.waMessageId(),
        log.status().name(),
        log.costRs(),
        Timestamp.from(log.sentAt()),
        ts(log.deliveredAt()),
        ts(log.readAt()),
        log.errorCode(),
        log.errorMessage());
  }

  @Override
  public Optional<WhatsAppDeliveryLog> findById(UUID id) {
    List<WhatsAppDeliveryLog> rows = jdbc.query(SELECT + " WHERE id = ?", (rs, i) -> map(rs), id);
    return rows.stream().findFirst();
  }

  @Override
  public Optional<WhatsAppDeliveryLog> findByWaMessageId(String waMessageId) {
    List<WhatsAppDeliveryLog> rows =
        jdbc.query(SELECT + " WHERE wa_message_id = ?", (rs, i) -> map(rs), waMessageId);
    return rows.stream().findFirst();
  }

  @Override
  public boolean markDelivered(String waMessageId, Instant deliveredAt) {
    int n =
        jdbc.update(
            """
            UPDATE whatsapp_delivery_logs
            SET status = CASE
                  WHEN status = 'READ' THEN status
                  ELSE 'DELIVERED'
                END,
                delivered_at = COALESCE(delivered_at, ?)
            WHERE wa_message_id = ?
            """,
            Timestamp.from(deliveredAt),
            waMessageId);
    return n > 0;
  }

  @Override
  public boolean markRead(String waMessageId, Instant readAt) {
    int n =
        jdbc.update(
            """
            UPDATE whatsapp_delivery_logs
            SET status = 'READ',
                delivered_at = COALESCE(delivered_at, ?),
                read_at = COALESCE(read_at, ?)
            WHERE wa_message_id = ?
            """,
            Timestamp.from(readAt),
            Timestamp.from(readAt),
            waMessageId);
    return n > 0;
  }

  @Override
  public boolean markFailed(String waMessageId, String errorCode, String errorMessage) {
    int n =
        jdbc.update(
            """
            UPDATE whatsapp_delivery_logs
            SET status = 'FAILED',
                error_code = COALESCE(?, error_code),
                error_message = COALESCE(?, error_message)
            WHERE wa_message_id = ?
            """,
            errorCode,
            errorMessage,
            waMessageId);
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
    if (filter.templateName() != null) {
      where.append(" AND template_name = ?");
      args.add(filter.templateName());
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
            "SELECT COUNT(*) FROM whatsapp_delivery_logs" + where, Integer.class, args.toArray());
    long count = total == null ? 0L : total.longValue();
    int offset = (filter.page() - 1) * filter.limit();
    List<Object> pageArgs = new ArrayList<>(args);
    pageArgs.add(filter.limit());
    pageArgs.add(offset);
    List<WhatsAppDeliveryLog> rows =
        jdbc.query(
            SELECT + where + " ORDER BY sent_at DESC LIMIT ? OFFSET ?",
            (rs, i) -> map(rs),
            pageArgs.toArray());
    return new Page(rows, count);
  }

  private WhatsAppDeliveryLog map(ResultSet rs) throws SQLException {
    return new WhatsAppDeliveryLog(
        (UUID) rs.getObject("id"),
        rs.getString("to_phone"),
        rs.getString("template_name"),
        parseComponents(rs.getString("components_json")),
        rs.getString("wa_message_id"),
        WhatsAppLogStatus.valueOf(rs.getString("status")),
        rs.getBigDecimal("cost_rs"),
        rs.getTimestamp("sent_at").toInstant(),
        instant(rs.getTimestamp("delivered_at")),
        instant(rs.getTimestamp("read_at")),
        rs.getString("error_code"),
        rs.getString("error_message"));
  }

  private String toJson(List<Map<String, Object>> components) {
    try {
      return mapper.writeValueAsString(components);
    } catch (JsonProcessingException e) {
      return "[]";
    }
  }

  private List<Map<String, Object>> parseComponents(String json) {
    if (json == null || json.isBlank()) {
      return List.of();
    }
    try {
      return mapper.readValue(json, new TypeReference<>() {});
    } catch (JsonProcessingException e) {
      return List.of();
    }
  }

  private static Instant instant(Timestamp ts) {
    return ts == null ? null : ts.toInstant();
  }

  private static Timestamp ts(Instant instant) {
    return instant == null ? null : Timestamp.from(instant);
  }
}
