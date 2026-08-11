package com.nammamedmate.notification.adapter.out.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.notification.application.port.out.WhatsAppTemplateStore;
import com.nammamedmate.notification.domain.WhatsAppCategory;
import com.nammamedmate.notification.domain.WhatsAppTemplate;
import com.nammamedmate.notification.domain.WhatsAppTemplateStatus;
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
public class JdbcWhatsAppTemplateStore implements WhatsAppTemplateStore {

  private static final String SELECT =
      """
      SELECT id, template_name, category, language, status, body_text,
             header_json, footer_text, buttons_json, meta_template_id,
             rejection_reason, submitted_at, approved_at, last_used_at
      FROM whatsapp_templates
      """;

  private final JdbcTemplate jdbc;
  private final ObjectMapper mapper;

  public JdbcWhatsAppTemplateStore(JdbcTemplate jdbc, ObjectMapper mapper) {
    this.jdbc = jdbc;
    this.mapper = mapper;
  }

  @Override
  public Optional<WhatsAppTemplate> findByName(String templateName) {
    List<WhatsAppTemplate> rows =
        jdbc.query(SELECT + " WHERE template_name = ?", (rs, i) -> map(rs), templateName);
    return rows.stream().findFirst();
  }

  @Override
  public boolean exists(String templateName) {
    Integer n =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM whatsapp_templates WHERE template_name = ?",
            Integer.class,
            templateName);
    return n != null && n > 0;
  }

  @Override
  public void insert(WhatsAppTemplate template) {
    jdbc.update(
        """
        INSERT INTO whatsapp_templates (
          id, template_name, category, language, status, body_text,
          header_json, footer_text, buttons_json, meta_template_id,
          rejection_reason, submitted_at, approved_at, last_used_at
        ) VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?::jsonb, ?, ?, ?, ?, ?)
        """,
        template.id(),
        template.templateName(),
        template.category().name(),
        template.language(),
        template.status().name(),
        template.bodyText(),
        toJson(template.header()),
        template.footerText(),
        toJson(template.buttons()),
        template.metaTemplateId(),
        template.rejectionReason(),
        Timestamp.from(template.submittedAt()),
        ts(template.approvedAt()),
        ts(template.lastUsedAt()));
  }

  @Override
  public void touchLastUsed(String templateName, Instant at) {
    jdbc.update(
        "UPDATE whatsapp_templates SET last_used_at = ? WHERE template_name = ?",
        Timestamp.from(at),
        templateName);
  }

  @Override
  public List<WhatsAppTemplate> list(WhatsAppCategory category, WhatsAppTemplateStatus status) {
    StringBuilder where = new StringBuilder(" WHERE 1=1");
    List<Object> args = new ArrayList<>();
    if (category != null) {
      where.append(" AND category = ?");
      args.add(category.name());
    }
    if (status != null) {
      where.append(" AND status = ?");
      args.add(status.name());
    }
    return jdbc.query(
        SELECT + where + " ORDER BY submitted_at DESC", (rs, i) -> map(rs), args.toArray());
  }

  private WhatsAppTemplate map(ResultSet rs) throws SQLException {
    return new WhatsAppTemplate(
        (UUID) rs.getObject("id"),
        rs.getString("template_name"),
        WhatsAppCategory.valueOf(rs.getString("category")),
        rs.getString("language"),
        WhatsAppTemplateStatus.valueOf(rs.getString("status")),
        rs.getString("body_text"),
        parseMap(rs.getString("header_json")),
        rs.getString("footer_text"),
        parseList(rs.getString("buttons_json")),
        rs.getString("meta_template_id"),
        rs.getString("rejection_reason"),
        instant(rs.getTimestamp("submitted_at")),
        instant(rs.getTimestamp("approved_at")),
        instant(rs.getTimestamp("last_used_at")));
  }

  private String toJson(Object value) {
    if (value == null) {
      return null;
    }
    try {
      return mapper.writeValueAsString(value);
    } catch (JsonProcessingException e) {
      return null;
    }
  }

  private Map<String, Object> parseMap(String json) {
    if (json == null) {
      return null;
    }
    if (json.isBlank()) {
      return null;
    }
    try {
      return mapper.readValue(json, new TypeReference<>() {});
    } catch (JsonProcessingException e) {
      return null;
    }
  }

  private List<Map<String, Object>> parseList(String json) {
    if (json == null) {
      return List.of();
    }
    if (json.isBlank()) {
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
