package com.nammamedmate.automation.adapter.out.persistence;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.automation.application.port.out.ActionRegistryPort;
import com.nammamedmate.automation.domain.ActionDefinition;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcActionRegistryAdapter implements ActionRegistryPort {

  private static final TypeReference<List<String>> STRINGS = new TypeReference<>() {};

  private final JdbcTemplate jdbc;
  private final ObjectMapper objectMapper;

  public JdbcActionRegistryAdapter(JdbcTemplate jdbc, ObjectMapper objectMapper) {
    this.jdbc = jdbc;
    this.objectMapper = objectMapper;
  }

  @Override
  public List<ActionDefinition> listAll() {
    return jdbc.query(
        """
        SELECT action_id, category, name, description, required_params_schema,
               optional_params_schema, is_reversible, always_require_approval,
               auto_approval_limit_paise
        FROM action_registry
        ORDER BY category, action_id
        """,
        (rs, i) -> mapRow(rs));
  }

  @Override
  public java.util.Optional<ActionDefinition> findById(String actionId) {
    if (actionId == null || actionId.isBlank()) {
      return java.util.Optional.empty();
    }
    List<ActionDefinition> rows =
        jdbc.query(
            """
            SELECT action_id, category, name, description, required_params_schema,
                   optional_params_schema, is_reversible, always_require_approval,
                   auto_approval_limit_paise
            FROM action_registry
            WHERE action_id = ?
            """,
            (rs, i) -> mapRow(rs),
            actionId.trim());
    return rows.stream().findFirst();
  }

  private ActionDefinition mapRow(ResultSet rs) throws SQLException {
    Long limit =
        rs.getObject("auto_approval_limit_paise") == null
            ? null
            : rs.getLong("auto_approval_limit_paise");
    return new ActionDefinition(
        rs.getString("action_id"),
        rs.getString("category"),
        rs.getString("name"),
        rs.getString("description"),
        readStrings(rs.getString("required_params_schema")),
        readStrings(rs.getString("optional_params_schema")),
        rs.getBoolean("is_reversible"),
        rs.getBoolean("always_require_approval"),
        limit);
  }

  private List<String> readStrings(String json) {
    if (json == null || json.isBlank()) {
      return List.of();
    }
    try {
      return objectMapper.readValue(json, STRINGS);
    } catch (Exception ex) {
      return List.of();
    }
  }
}
