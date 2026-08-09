package com.nammamedmate.integration.adapter.out.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.integration.application.port.out.CommunicationConfigAuditStore;
import com.nammamedmate.integration.domain.CommunicationConfigAudit;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class JdbcCommunicationConfigAuditStore implements CommunicationConfigAuditStore {

  private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};

  private final JdbcTemplate jdbc;
  private final ObjectMapper mapper;

  public JdbcCommunicationConfigAuditStore(JdbcTemplate jdbc, ObjectMapper mapper) {
    this.jdbc = jdbc;
    this.mapper = mapper;
  }

  @Override
  public void insert(CommunicationConfigAudit audit) {
    jdbc.update(
        """
        INSERT INTO communication_config_audit (
          id, channel, changed_by, changed_fields, connectivity_test_result, changed_at
        ) VALUES (?, ?, ?, ?::jsonb, ?, ?)
        """,
        audit.id(),
        audit.channel(),
        audit.changedBy(),
        json(audit.changedFields()),
        audit.connectivityTestResult(),
        Timestamp.from(audit.changedAt()));
  }

  @Override
  public List<CommunicationConfigAudit> findByChannel(String channel) {
    return jdbc.query(
        """
        SELECT * FROM communication_config_audit
         WHERE channel = ?
         ORDER BY changed_at DESC
        """,
        this::mapRow,
        channel);
  }

  private CommunicationConfigAudit mapRow(ResultSet rs, int rowNum) throws SQLException {
    return new CommunicationConfigAudit(
        (UUID) rs.getObject("id"),
        rs.getString("channel"),
        (UUID) rs.getObject("changed_by"),
        parseMap(rs.getString("changed_fields")),
        rs.getString("connectivity_test_result"),
        rs.getTimestamp("changed_at").toInstant());
  }

  private String json(Map<String, Object> map) {
    try {
      return mapper.writeValueAsString(map);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Failed to serialize audit fields", e);
    }
  }

  private Map<String, Object> parseMap(String json) {
    if (json == null || json.isBlank()) {
      return Map.of();
    }
    try {
      return mapper.readValue(json, MAP);
    } catch (JsonProcessingException e) {
      return Map.of();
    }
  }
}
