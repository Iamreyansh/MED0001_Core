package com.nammamedmate.automation.adapter.out.persistence;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.automation.application.port.out.TriggerRegistryPort;
import com.nammamedmate.automation.domain.TriggerDefinition;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcTriggerRegistryAdapter implements TriggerRegistryPort {

  private static final TypeReference<List<Map<String, Object>>> PARAMS_TYPE =
      new TypeReference<>() {};

  private final JdbcTemplate jdbc;
  private final ObjectMapper objectMapper;

  public JdbcTriggerRegistryAdapter(JdbcTemplate jdbc, ObjectMapper objectMapper) {
    this.jdbc = jdbc;
    this.objectMapper = objectMapper;
  }

  @Override
  public List<TriggerDefinition> listActive(String categoryOrNull) {
    if (categoryOrNull == null || categoryOrNull.isBlank()) {
      return jdbc.query(
          """
          SELECT trigger_id, category, name, description, parameters_schema,
                 available_conditions, available_context_vars, is_active
          FROM trigger_registry
          WHERE is_active = TRUE
          ORDER BY category, trigger_id
          """,
          (rs, i) -> mapRow(rs));
    }
    return jdbc.query(
        """
        SELECT trigger_id, category, name, description, parameters_schema,
               available_conditions, available_context_vars, is_active
        FROM trigger_registry
        WHERE is_active = TRUE AND category = ?
        ORDER BY trigger_id
        """,
        (rs, i) -> mapRow(rs),
        categoryOrNull.trim().toUpperCase());
  }

  @Override
  public java.util.Optional<TriggerDefinition> findById(String triggerId) {
    if (triggerId == null || triggerId.isBlank()) {
      return java.util.Optional.empty();
    }
    List<TriggerDefinition> rows =
        jdbc.query(
            """
            SELECT trigger_id, category, name, description, parameters_schema,
                   available_conditions, available_context_vars, is_active
            FROM trigger_registry
            WHERE trigger_id = ?
            """,
            (rs, i) -> mapRow(rs),
            triggerId.trim());
    return rows.stream().findFirst();
  }

  private TriggerDefinition mapRow(ResultSet rs) throws SQLException {
    return new TriggerDefinition(
        rs.getString("trigger_id"),
        rs.getString("category"),
        rs.getString("name"),
        rs.getString("description"),
        readJsonList(rs.getString("parameters_schema")),
        toList(rs.getArray("available_conditions")),
        toList(rs.getArray("available_context_vars")),
        rs.getBoolean("is_active"));
  }

  private List<Map<String, Object>> readJsonList(String json) {
    if (json == null || json.isBlank()) {
      return List.of();
    }
    try {
      return objectMapper.readValue(json, PARAMS_TYPE);
    } catch (Exception ex) {
      return List.of();
    }
  }

  private static List<String> toList(Array array) throws SQLException {
    if (array == null) {
      return List.of();
    }
    Object[] vals = (Object[]) array.getArray();
    return Arrays.stream(vals).map(String::valueOf).toList();
  }
}
