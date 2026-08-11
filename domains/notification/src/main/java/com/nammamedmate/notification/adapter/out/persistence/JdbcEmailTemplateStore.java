package com.nammamedmate.notification.adapter.out.persistence;

import com.nammamedmate.notification.application.port.out.EmailTemplateStore;
import com.nammamedmate.notification.domain.EmailCategory;
import com.nammamedmate.notification.domain.EmailTemplate;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class JdbcEmailTemplateStore implements EmailTemplateStore {

  private static final String SELECT =
      """
      SELECT template_id, name, subject, html_body, text_body, category,
             is_active, version, created_by, created_at, updated_at
      FROM email_templates
      """;

  private final JdbcTemplate jdbc;

  public JdbcEmailTemplateStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Optional<EmailTemplate> findById(String templateId) {
    List<EmailTemplate> rows =
        jdbc.query(SELECT + " WHERE template_id = ?", (rs, i) -> map(rs), templateId);
    return rows.stream().findFirst();
  }

  @Override
  public boolean exists(String templateId) {
    Integer n =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM email_templates WHERE template_id = ?",
            Integer.class,
            templateId);
    if (n == null) {
      return false;
    }
    return n > 0;
  }

  @Override
  public void upsert(EmailTemplate template) {
    jdbc.update(
        """
        INSERT INTO email_templates (
          template_id, name, subject, html_body, text_body, category,
          is_active, version, created_by, created_at, updated_at
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        ON CONFLICT (template_id) DO UPDATE SET
          name = EXCLUDED.name,
          subject = EXCLUDED.subject,
          html_body = EXCLUDED.html_body,
          text_body = EXCLUDED.text_body,
          category = EXCLUDED.category,
          is_active = EXCLUDED.is_active,
          version = EXCLUDED.version,
          updated_at = EXCLUDED.updated_at
        """,
        template.templateId(),
        template.name(),
        template.subject(),
        template.htmlBody(),
        template.textBody(),
        template.category().name(),
        template.active(),
        template.version(),
        template.createdBy(),
        Timestamp.from(template.createdAt()),
        Timestamp.from(template.updatedAt()));
  }

  @Override
  public List<EmailTemplate> list(EmailCategory category, Boolean active) {
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

  private static EmailTemplate map(ResultSet rs) throws SQLException {
    Timestamp created = rs.getTimestamp("created_at");
    Timestamp updated = rs.getTimestamp("updated_at");
    return new EmailTemplate(
        rs.getString("template_id"),
        rs.getString("name"),
        rs.getString("subject"),
        rs.getString("html_body"),
        rs.getString("text_body"),
        EmailCategory.valueOf(rs.getString("category")),
        rs.getBoolean("is_active"),
        rs.getInt("version"),
        (UUID) rs.getObject("created_by"),
        created == null ? null : created.toInstant(),
        updated == null ? null : updated.toInstant());
  }
}
