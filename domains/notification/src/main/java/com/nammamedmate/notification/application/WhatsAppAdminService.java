package com.nammamedmate.notification.application;

import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.notification.application.port.out.MetaWhatsAppClientPort;
import com.nammamedmate.notification.application.port.out.WhatsAppDeliveryLogStore;
import com.nammamedmate.notification.application.port.out.WhatsAppTemplateStore;
import com.nammamedmate.notification.domain.WhatsAppCategory;
import com.nammamedmate.notification.domain.WhatsAppDeliveryLog;
import com.nammamedmate.notification.domain.WhatsAppLogStatus;
import com.nammamedmate.notification.domain.WhatsAppTemplate;
import com.nammamedmate.notification.domain.WhatsAppTemplateStatus;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

@Service
public class WhatsAppAdminService {

  private static final Pattern LANGUAGE = Pattern.compile("^[a-z]{2}(_[A-Z]{2})?$");

  private final WhatsAppTemplateStore templates;
  private final WhatsAppDeliveryLogStore logs;
  private final MetaWhatsAppClientPort meta;
  private final Clock clock;

  public WhatsAppAdminService(
      WhatsAppTemplateStore templates,
      WhatsAppDeliveryLogStore logs,
      MetaWhatsAppClientPort meta,
      Clock clock) {
    this.templates = templates;
    this.logs = logs;
    this.meta = meta;
    this.clock = clock;
  }

  public Map<String, Object> listTemplates(String category, String status) {
    WhatsAppCategory cat = null;
    if (category != null && !category.isBlank()) {
      try {
        cat = WhatsAppCategory.parse(category);
      } catch (IllegalArgumentException e) {
        throw new AppException(
            "INVALID_CATEGORY", "category not in UTILITY/MARKETING/AUTHENTICATION", 422);
      }
    }
    WhatsAppTemplateStatus st = null;
    if (status != null && !status.isBlank()) {
      try {
        st = WhatsAppTemplateStatus.parse(status);
      } catch (IllegalArgumentException e) {
        throw new AppException("INVALID_STATUS", "status not in APPROVED/PENDING/REJECTED", 400);
      }
    }
    List<Map<String, Object>> rows = new ArrayList<>();
    for (WhatsAppTemplate t : templates.list(cat, st)) {
      rows.add(toTemplateRow(t));
    }
    return Map.of("templates", rows);
  }

  public Map<String, Object> submitTemplate(
      String name,
      String category,
      String language,
      String body,
      Map<String, Object> header,
      String footer,
      List<Map<String, Object>> buttons) {
    String templateName = name == null ? "" : name.trim();
    if (templateName.isEmpty()) {
      throw new AppException("MISSING_TEMPLATE_NAME", "name is required", 400);
    }
    if (templates.exists(templateName)) {
      throw new AppException("TEMPLATE_NAME_EXISTS", "template_name already registered", 409);
    }
    WhatsAppCategory cat;
    try {
      cat = WhatsAppCategory.parse(category);
    } catch (IllegalArgumentException e) {
      throw new AppException(
          "INVALID_CATEGORY", "category not in UTILITY/MARKETING/AUTHENTICATION", 422);
    }
    String lang = language == null ? "" : language.trim();
    if (!LANGUAGE.matcher(lang).matches()) {
      throw new AppException("INVALID_LANGUAGE", "language code not supported", 422);
    }
    String bodyText = body == null ? "" : body;
    List<Map<String, Object>> buttonList = buttons == null ? List.of() : buttons;

    MetaWhatsAppClientPort.SubmitTemplateResult result =
        meta.submitTemplate(
            new MetaWhatsAppClientPort.SubmitTemplateRequest(
                templateName, cat.name(), lang, bodyText, header, footer, buttonList));
    if (!result.success()) {
      throw new AppException("META_API_UNAVAILABLE", "Meta Cloud API returned error", 503);
    }

    Instant now = clock.instant();
    WhatsAppTemplate saved =
        new WhatsAppTemplate(
            Ids.newId(),
            templateName,
            cat,
            lang,
            WhatsAppTemplateStatus.PENDING,
            bodyText,
            header,
            footer,
            buttonList,
            result.metaTemplateId(),
            null,
            now,
            null,
            null);
    templates.insert(saved);

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("template_name", templateName);
    data.put("status", WhatsAppTemplateStatus.PENDING.name());
    data.put("submitted_at", now.toString());
    data.put("estimated_review_days", 2);
    return data;
  }

  public record LogPage(Map<String, Object> data, int page, int limit, long total) {
    public LogPage {
      data = data == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(data));
    }
  }

  public LogPage listLogs(
      String toPhone,
      String templateName,
      String status,
      Instant dateFrom,
      Instant dateTo,
      Integer page,
      Integer limit) {
    int p = page == null || page < 1 ? 1 : page;
    int lim = limit == null || limit < 1 ? 20 : Math.min(limit, 100);
    WhatsAppLogStatus st = null;
    if (status != null && !status.isBlank()) {
      try {
        st = WhatsAppLogStatus.parse(status);
      } catch (IllegalArgumentException e) {
        throw new AppException("INVALID_STATUS", "status not in allowed set", 400);
      }
    }
    WhatsAppDeliveryLogStore.Page result =
        logs.list(
            new WhatsAppDeliveryLogStore.ListFilter(
                blankToNull(toPhone), blankToNull(templateName), st, dateFrom, dateTo, p, lim));
    List<Map<String, Object>> rows = new ArrayList<>();
    for (WhatsAppDeliveryLog log : result.logs()) {
      rows.add(toLogRow(log));
    }
    return new LogPage(Map.of("logs", rows), p, lim, result.total());
  }

  private static Map<String, Object> toTemplateRow(WhatsAppTemplate t) {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("template_name", t.templateName());
    row.put("category", t.category().name());
    row.put("language", t.language());
    row.put("status", t.status().name());
    row.put("body_text", t.bodyText());
    row.put("buttons", t.buttons());
    row.put("last_used_at", t.lastUsedAt() == null ? null : t.lastUsedAt().toString());
    if (t.status() == WhatsAppTemplateStatus.REJECTED) {
      row.put("rejection_reason", t.rejectionReason());
    } else {
      row.put("rejection_reason", null);
    }
    return row;
  }

  private Map<String, Object> toLogRow(WhatsAppDeliveryLog log) {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("log_id", log.id().toString());
    row.put("to_phone", log.toPhone());
    row.put("template_name", log.templateName());
    templates
        .findByName(log.templateName())
        .ifPresentOrElse(
            t -> row.put("category", t.category().name()), () -> row.put("category", null));
    row.put("wa_message_id", log.waMessageId());
    row.put("status", log.status().name());
    row.put("cost_rs", log.costRs());
    row.put("sent_at", log.sentAt() == null ? null : log.sentAt().toString());
    row.put("delivered_at", log.deliveredAt() == null ? null : log.deliveredAt().toString());
    row.put("read_at", log.readAt() == null ? null : log.readAt().toString());
    row.put("error_message", log.errorMessage());
    return row;
  }

  private static String blankToNull(String s) {
    return s == null || s.isBlank() ? null : s.trim();
  }
}
