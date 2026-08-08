package com.nammamedmate.settings.adapter.out.persistence;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.settings.application.port.out.PlatformAuditLogStore;
import com.nammamedmate.settings.domain.AuditRedaction;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class JdbcPlatformAuditLogStore implements PlatformAuditLogStore {

  private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};

  private final JdbcTemplate jdbc;
  private final ObjectMapper objectMapper;

  public JdbcPlatformAuditLogStore(JdbcTemplate jdbc, ObjectMapper objectMapper) {
    this.jdbc = jdbc;
    this.objectMapper = objectMapper;
  }

  @Override
  public void append(
      UUID id,
      UUID actorId,
      String actorName,
      String actorRole,
      String actorType,
      String action,
      String resourceType,
      UUID resourceId,
      Map<String, Object> beforeState,
      Map<String, Object> afterState,
      Map<String, Object> metadata,
      String ipAddress,
      String userAgent,
      Instant timestamp) {
    Map<String, Object> before = AuditRedaction.redactMap(beforeState);
    Map<String, Object> after = AuditRedaction.redactMap(afterState);
    Map<String, Object> meta = AuditRedaction.redactMap(metadata);
    Map<String, Object> payload = new LinkedHashMap<>();
    if (before != null) {
      payload.put("before", before);
    }
    if (after != null) {
      payload.put("after", after);
    }
    String type = normalizeResourceType(resourceType);
    jdbc.update(
        """
        INSERT INTO audit_log (
          id, entity_type, entity_id, action, actor_id, actor_role, payload, ip_address, created_at,
          actor_name, actor_type, resource_type, resource_id, before_state, after_state, metadata,
          user_agent, "timestamp"
        ) VALUES (
          ?, ?, ?, ?, ?, ?, ?::jsonb, CAST(? AS inet), ?,
          ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?::jsonb, ?, ?
        )
        """,
        id,
        type,
        resourceId,
        action,
        actorId,
        actorRole == null ? "unknown" : actorRole,
        toJson(payload),
        blankToDefaultIp(ipAddress),
        Timestamp.from(timestamp),
        blankToUnknown(actorName),
        blankToAdmin(actorType),
        type,
        resourceId,
        toJsonOrNull(before),
        toJsonOrNull(after),
        toJsonOrNull(meta),
        userAgent,
        Timestamp.from(timestamp));
  }

  private static String normalizeResourceType(String resourceType) {
    if (resourceType == null || resourceType.isBlank()) {
      return "unknown";
    }
    return resourceType.trim().toLowerCase(Locale.ROOT);
  }

  private static String blankToUnknown(String value) {
    if (value == null || value.isBlank()) {
      return "unknown";
    }
    return value;
  }

  private static String blankToAdmin(String value) {
    if (value == null || value.isBlank()) {
      return "ADMIN";
    }
    return value;
  }

  @Override
  public PageResult list(ListFilter filter) {
    StringBuilder where = new StringBuilder(" WHERE archived_at IS NULL ");
    List<Object> args = new ArrayList<>();
    if (filter.actorId() != null) {
      where.append(" AND actor_id = ? ");
      args.add(filter.actorId());
    }
    if (filter.actorType() != null) {
      where.append(" AND actor_type = ? ");
      args.add(filter.actorType());
    }
    if (filter.resourceType() != null) {
      where.append(" AND LOWER(resource_type) = LOWER(?) ");
      args.add(filter.resourceType());
    }
    if (filter.resourceId() != null) {
      where.append(" AND resource_id = ? ");
      args.add(filter.resourceId());
    }
    if (filter.action() != null) {
      where.append(" AND action = ? ");
      args.add(filter.action());
    }
    if (filter.from() != null) {
      where.append(" AND \"timestamp\" >= ? ");
      args.add(Timestamp.from(filter.from()));
    }
    if (filter.to() != null) {
      where.append(" AND \"timestamp\" <= ? ");
      args.add(Timestamp.from(filter.to()));
    }

    String sortCol =
        switch (filter.sort() == null ? "timestamp" : filter.sort()) {
          case "action" -> "action";
          case "resource_type" -> "resource_type";
          default -> "\"timestamp\"";
        };
    String order = "asc".equalsIgnoreCase(filter.order()) ? "ASC" : "DESC";

    Long total =
        jdbc.queryForObject("SELECT COUNT(*) FROM audit_log" + where, Long.class, args.toArray());
    List<Object> pageArgs = new ArrayList<>(args);
    pageArgs.add(filter.limit());
    pageArgs.add(filter.offset());
    List<AuditLogRow> rows =
        jdbc.query(
            """
            SELECT id, actor_id, actor_name, actor_role, actor_type, action, resource_type, resource_id,
                   before_state, after_state, metadata, host(ip_address) AS ip_address, user_agent, "timestamp"
            FROM audit_log
            """
                + where
                + " ORDER BY "
                + sortCol
                + " "
                + order
                + " LIMIT ? OFFSET ?",
            (rs, i) -> mapRow(rs),
            pageArgs.toArray());
    return new PageResult(rows, total == null ? 0L : total);
  }

  @Override
  public Optional<AuditLogRow> findById(UUID id) {
    List<AuditLogRow> rows =
        jdbc.query(
            """
            SELECT id, actor_id, actor_name, actor_role, actor_type, action, resource_type, resource_id,
                   before_state, after_state, metadata, host(ip_address) AS ip_address, user_agent, "timestamp"
            FROM audit_log
            WHERE id = ? AND archived_at IS NULL
            """,
            (rs, i) -> mapRow(rs),
            id);
    return rows.stream().findFirst();
  }

  @Override
  public List<AuditLogRow> listForArchive(Instant olderThan, int limit) {
    return jdbc.query(
        """
        SELECT id, actor_id, actor_name, actor_role, actor_type, action, resource_type, resource_id,
               before_state, after_state, metadata, host(ip_address) AS ip_address, user_agent, "timestamp"
        FROM audit_log
        WHERE archived_at IS NULL AND "timestamp" < ?
        ORDER BY "timestamp" ASC
        LIMIT ?
        """,
        (rs, i) -> mapRow(rs),
        Timestamp.from(olderThan),
        limit);
  }

  @Override
  public void markArchived(UUID id, Instant archivedAt) {
    jdbc.update(
        "UPDATE audit_log SET archived_at = ? WHERE id = ? AND archived_at IS NULL",
        Timestamp.from(archivedAt),
        id);
  }

  private AuditLogRow mapRow(ResultSet rs) throws SQLException {
    return new AuditLogRow(
        (UUID) rs.getObject("id"),
        (UUID) rs.getObject("actor_id"),
        rs.getString("actor_name"),
        rs.getString("actor_role"),
        rs.getString("actor_type"),
        rs.getString("action"),
        rs.getString("resource_type"),
        (UUID) rs.getObject("resource_id"),
        readMap(rs.getString("before_state")),
        readMap(rs.getString("after_state")),
        readMap(rs.getString("metadata")),
        rs.getString("ip_address"),
        rs.getString("user_agent"),
        rs.getTimestamp("timestamp").toInstant());
  }

  private Map<String, Object> readMap(String json) {
    if (json == null || json.isBlank()) {
      return null;
    }
    try {
      return objectMapper.readValue(json, MAP);
    } catch (Exception ex) {
      return Map.of();
    }
  }

  private String toJson(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (Exception ex) {
      throw new IllegalStateException(ex);
    }
  }

  private String toJsonOrNull(Object value) {
    return value == null ? null : toJson(value);
  }

  private static String blankToDefaultIp(String ip) {
    if (ip == null || ip.isBlank()) {
      return "0.0.0.0";
    }
    return ip.trim();
  }
}
