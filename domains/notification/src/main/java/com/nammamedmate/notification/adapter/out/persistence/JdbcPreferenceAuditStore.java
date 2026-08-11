package com.nammamedmate.notification.adapter.out.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.notification.application.port.out.PreferenceAuditStore;
import com.nammamedmate.notification.domain.PreferenceAuditEntry;
import java.sql.Timestamp;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class JdbcPreferenceAuditStore implements PreferenceAuditStore {

  private final JdbcTemplate jdbc;
  private final ObjectMapper mapper;

  public JdbcPreferenceAuditStore(JdbcTemplate jdbc, ObjectMapper mapper) {
    this.jdbc = jdbc;
    this.mapper = mapper;
  }

  @Override
  public void insert(PreferenceAuditEntry entry) {
    jdbc.update(
        """
        INSERT INTO notification_preference_audit (
          id, entity_type, entity_id, changed_by, change_source,
          old_values, new_values, changed_at
        ) VALUES (?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?)
        """,
        entry.id(),
        entry.entityType().name(),
        entry.entityId(),
        entry.changedBy(),
        entry.changeSource().name(),
        toJson(entry.oldValues()),
        toJson(entry.newValues()),
        Timestamp.from(entry.changedAt()));
  }

  private String toJson(Map<String, Object> values) {
    try {
      return mapper.writeValueAsString(values);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Failed to serialize preference audit JSON", e);
    }
  }
}
