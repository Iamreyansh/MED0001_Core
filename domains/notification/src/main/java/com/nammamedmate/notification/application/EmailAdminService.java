package com.nammamedmate.notification.application;

import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.notification.application.port.out.EmailDeliveryLogStore;
import com.nammamedmate.notification.application.port.out.EmailTemplateStore;
import com.nammamedmate.notification.domain.EmailCategory;
import com.nammamedmate.notification.domain.EmailDeliveryLog;
import com.nammamedmate.notification.domain.EmailLogStatus;
import com.nammamedmate.notification.domain.EmailTemplate;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class EmailAdminService {

  private final EmailTemplateStore templates;
  private final EmailDeliveryLogStore logs;
  private final Clock clock;

  public EmailAdminService(EmailTemplateStore templates, EmailDeliveryLogStore logs, Clock clock) {
    this.templates = templates;
    this.logs = logs;
    this.clock = clock;
  }

  public Map<String, Object> listTemplates(String category, Boolean isActive) {
    EmailCategory cat = null;
    if (category != null && !category.isBlank()) {
      try {
        cat = EmailCategory.parse(category);
      } catch (IllegalArgumentException e) {
        throw new AppException(
            "INVALID_CATEGORY", "category not in TRANSACTIONAL/LIFECYCLE/MARKETING", 422);
      }
    }
    List<Map<String, Object>> rows = new ArrayList<>();
    for (EmailTemplate t : templates.list(cat, isActive)) {
      rows.add(toTemplateRow(t));
    }
    return Map.of("templates", rows);
  }

  public Map<String, Object> upsertTemplate(
      UUID createdBy,
      String templateId,
      String name,
      String subject,
      String htmlBody,
      String textBody,
      String category) {
    String id = templateId == null ? "" : templateId.trim();
    if (id.isEmpty()) {
      throw new AppException("MISSING_TEMPLATE_ID", "template_id is required", 400);
    }
    EmailCategory cat;
    try {
      cat = EmailCategory.parse(category);
    } catch (IllegalArgumentException e) {
      throw new AppException(
          "INVALID_CATEGORY", "category not in TRANSACTIONAL/LIFECYCLE/MARKETING", 422);
    }
    String html = htmlBody == null ? "" : htmlBody;
    String text =
        textBody == null || textBody.isBlank()
            ? HandlebarsRenderer.htmlToPlainText(html)
            : textBody;
    Instant now = clock.instant();
    boolean exists = templates.exists(id);
    int version = 1;
    Instant createdAt = now;
    if (exists) {
      EmailTemplate existing = templates.findById(id).orElseThrow();
      version = existing.version() + 1;
      createdAt = existing.createdAt();
      if (existing.createdBy() != null) {
        createdBy = existing.createdBy();
      }
    }
    String displayName = id;
    if (name != null) {
      displayName = name.trim();
    }
    String subjectLine = "";
    if (subject != null) {
      subjectLine = subject;
    }
    EmailTemplate saved =
        new EmailTemplate(
            id,
            displayName,
            subjectLine,
            html,
            text,
            cat,
            true,
            version,
            createdBy,
            createdAt,
            now);
    templates.upsert(saved);
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("template_id", id);
    data.put("category", cat.name());
    data.put("created_at", createdAt.toString());
    data.put("version", version);
    return data;
  }

  public record LogPage(Map<String, Object> data, int page, int limit, long total) {
    public LogPage {
      data =
          data == null
              ? Map.of()
              : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(data));
    }
  }

  public LogPage listLogs(
      String toEmail,
      String templateId,
      String status,
      Instant dateFrom,
      Instant dateTo,
      Integer page,
      Integer limit) {
    int p = page == null || page < 1 ? 1 : page;
    int lim = limit == null || limit < 1 ? 20 : Math.min(limit, 100);
    EmailLogStatus st = null;
    if (status != null && !status.isBlank()) {
      try {
        st = EmailLogStatus.parse(status);
      } catch (IllegalArgumentException e) {
        throw new AppException("INVALID_STATUS", "status not in allowed set", 400);
      }
    }
    EmailDeliveryLogStore.Page result =
        logs.list(
            new EmailDeliveryLogStore.ListFilter(
                blankToNull(toEmail), blankToNull(templateId), st, dateFrom, dateTo, p, lim));
    List<Map<String, Object>> rows = new ArrayList<>();
    for (EmailDeliveryLog log : result.logs()) {
      rows.add(toLogRow(log));
    }
    return new LogPage(Map.of("logs", rows), p, lim, result.total());
  }

  private Map<String, Object> toTemplateRow(EmailTemplate t) {
    EmailDeliveryLogStore.TemplateStats stats = logs.statsForTemplate(t.templateId());
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("id", t.templateId());
    row.put("name", t.name());
    row.put("subject", t.subject());
    row.put("category", t.category().name());
    row.put("last_sent", stats.lastSent() == null ? null : stats.lastSent().toString());
    row.put("open_rate_pct", ratePct(stats.openedCount(), stats.sentCount()));
    row.put("click_rate_pct", ratePct(stats.clickedCount(), stats.sentCount()));
    return row;
  }

  private static Map<String, Object> toLogRow(EmailDeliveryLog log) {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("log_id", log.id().toString());
    row.put("to_email", log.toEmail());
    row.put("to_name", log.toName());
    row.put("template_id", log.templateId());
    row.put("subject", log.subject());
    row.put("provider", log.provider() == null ? null : log.provider().name());
    row.put("fallback_used", log.fallbackUsed());
    row.put("status", log.status().name());
    row.put("sent_at", log.sentAt() == null ? null : log.sentAt().toString());
    row.put("delivered_at", log.deliveredAt() == null ? null : log.deliveredAt().toString());
    row.put("opened_at", log.openedAt() == null ? null : log.openedAt().toString());
    row.put("clicked_at", log.clickedAt() == null ? null : log.clickedAt().toString());
    row.put("error_message", log.errorMessage());
    return row;
  }

  static double ratePct(long numerator, long denominator) {
    if (denominator <= 0) {
      return 0.0d;
    }
    return BigDecimal.valueOf(numerator * 100.0d / denominator)
        .setScale(1, RoundingMode.HALF_UP)
        .doubleValue();
  }

  private static String blankToNull(String s) {
    if (s == null) {
      return null;
    }
    String trimmed = s.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }
}
