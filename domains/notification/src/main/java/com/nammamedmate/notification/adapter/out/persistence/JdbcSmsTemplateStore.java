package com.nammamedmate.notification.adapter.out.persistence;

import com.nammamedmate.notification.application.port.out.SmsTemplateStore;
import com.nammamedmate.notification.domain.SmsCategory;
import com.nammamedmate.notification.domain.SmsTemplate;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class JdbcSmsTemplateStore implements SmsTemplateStore {

  private static final String SELECT =
      """
      SELECT template_id, content, category, dlt_template_id, sender_id,
             is_active, created_by, created_at
      FROM sms_templates
      """;

  private final JdbcTemplate jdbc;

  public JdbcSmsTemplateStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Optional<SmsTemplate> findById(String templateId) {
    List<SmsTemplate> rows =
        jdbc.query(SELECT + " WHERE template_id = ?", (rs, i) -> map(rs), templateId);
    return rows.stream().findFirst();
  }

  @Override
  public boolean exists(String templateId) {
    Integer n =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM sms_templates WHERE template_id = ?", Integer.class, templateId);
    return n != null && n > 0;
  }

  @Override
  public void insert(SmsTemplate template) {
    jdbc.update(
        """
        INSERT INTO sms_templates (
          template_id, content, category, dlt_template_id, sender_id,
          is_active, created_by, created_at
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        """,
        template.templateId(),
        template.content(),
        template.category().name(),
        template.dltTemplateId(),
        template.senderId(),
        template.active(),
        template.createdBy(),
        Timestamp.from(template.createdAt()));
  }

  @Override
  public List<SmsTemplate> list(SmsCategory category, Boolean active) {
    StringBuilder where = new StringBuilder(" WHERE 1=1");
    List<Object> args = new ArrayList<>();
    if (category != null) {
      where.append(" AND category = ?");
      args.add(category.name());
    }
    if (active != null) {
      where.append(" AND is_active = ?");
      args.add(active);
    }
    return jdbc.query(
        SELECT + where + " ORDER BY created_at DESC", (rs, i) -> map(rs), args.toArray());
  }

  private static SmsTemplate map(ResultSet rs) throws SQLException {
    Timestamp created = rs.getTimestamp("created_at");
    Instant createdAt = null;
    if (created != null) {
      createdAt = created.toInstant();
    }
    return new SmsTemplate(
        rs.getString("template_id"),
        rs.getString("content"),
        SmsCategory.valueOf(rs.getString("category")),
        rs.getString("dlt_template_id"),
        rs.getString("sender_id"),
        rs.getBoolean("is_active"),
        (UUID) rs.getObject("created_by"),
        createdAt);
  }
}
