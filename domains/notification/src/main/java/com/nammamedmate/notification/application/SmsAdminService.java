package com.nammamedmate.notification.application;

import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.notification.application.port.out.SmsDeliveryLogStore;
import com.nammamedmate.notification.application.port.out.SmsTemplateStore;
import com.nammamedmate.notification.domain.SmsCategory;
import com.nammamedmate.notification.domain.SmsDeliveryLog;
import com.nammamedmate.notification.domain.SmsLogStatus;
import com.nammamedmate.notification.domain.SmsTemplate;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class SmsAdminService {

  private final SmsTemplateStore templates;
  private final SmsDeliveryLogStore logs;
  private final Clock clock;

  public SmsAdminService(SmsTemplateStore templates, SmsDeliveryLogStore logs, Clock clock) {
    this.templates = templates;
    this.logs = logs;
    this.clock = clock;
  }

  public Map<String, Object> listTemplates(String category, Boolean isActive) {
    SmsCategory cat = null;
    if (category != null && !category.isBlank()) {
      try {
        cat = SmsCategory.parse(category);
      } catch (IllegalArgumentException e) {
        throw new AppException(
            "INVALID_CATEGORY", "category not in OTP/TRANSACTIONAL/PROMOTIONAL", 422);
      }
    }
    List<Map<String, Object>> rows = new ArrayList<>();
    for (SmsTemplate t : templates.list(cat, isActive)) {
      rows.add(toTemplateRow(t));
    }
    return Map.of("templates", rows);
  }

  public Map<String, Object> createTemplate(
      UUID createdBy,
      String templateId,
      String content,
      String category,
      String dltTemplateId,
      String senderId) {
    String id = templateId == null ? "" : templateId.trim();
    if (id.isEmpty()) {
      throw new AppException("MISSING_TEMPLATE_ID", "template_id is required", 400);
    }
    if (templates.exists(id)) {
      throw new AppException("TEMPLATE_ALREADY_EXISTS", "template_id already registered", 409);
    }
    SmsCategory cat;
    try {
      cat = SmsCategory.parse(category);
    } catch (IllegalArgumentException e) {
      throw new AppException(
          "INVALID_CATEGORY", "category not in OTP/TRANSACTIONAL/PROMOTIONAL", 422);
    }
    String sender =
        senderId == null || senderId.isBlank() ? SmsSendService.SENDER_ID : senderId.trim();
    Instant now = clock.instant();
    SmsTemplate saved =
        new SmsTemplate(
            id, content == null ? "" : content, cat, dltTemplateId, sender, true, createdBy, now);
    templates.insert(saved);
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("template_id", id);
    data.put("category", cat.name());
    data.put("is_active", true);
    data.put("created_at", now.toString());
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
      String toPhone,
      String templateId,
      String status,
      Instant dateFrom,
      Instant dateTo,
      Integer page,
      Integer limit) {
    int p = page == null || page < 1 ? 1 : page;
    int lim = limit == null || limit < 1 ? 20 : Math.min(limit, 100);
    SmsLogStatus st = null;
    if (status != null && !status.isBlank()) {
      try {
        st = SmsLogStatus.parse(status);
      } catch (IllegalArgumentException e) {
        throw new AppException("INVALID_STATUS", "status not in allowed set", 400);
      }
    }
    SmsDeliveryLogStore.Page result =
        logs.list(
            new SmsDeliveryLogStore.ListFilter(
                blankToNull(toPhone), blankToNull(templateId), st, dateFrom, dateTo, p, lim));
    List<Map<String, Object>> rows = new ArrayList<>();
    for (SmsDeliveryLog log : result.logs()) {
      rows.add(toLogRow(log));
    }
    return new LogPage(Map.of("logs", rows), p, lim, result.total());
  }

  private static Map<String, Object> toTemplateRow(SmsTemplate t) {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("template_id", t.templateId());
    row.put("content", t.content());
    row.put("category", t.category().name());
    row.put("dlt_template_id", t.dltTemplateId());
    row.put("sender_id", t.senderId());
    row.put("is_active", t.active());
    row.put("created_at", t.createdAt() == null ? null : t.createdAt().toString());
    return row;
  }

  private Map<String, Object> toLogRow(SmsDeliveryLog log) {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("log_id", log.id().toString());
    row.put("to_phone", log.toPhone());
    row.put("template_id", log.templateId());
    templates
        .findById(log.templateId())
        .ifPresentOrElse(
            t -> row.put("category", t.category().name()), () -> row.put("category", null));
    row.put("provider", log.provider() == null ? null : log.provider().name());
    row.put("provider_message_id", log.providerMessageId());
    row.put("fallback_used", log.fallbackUsed());
    row.put("status", log.status().name());
    row.put("cost_rs", log.costRs());
    row.put("sent_at", log.sentAt() == null ? null : log.sentAt().toString());
    row.put("delivered_at", log.deliveredAt() == null ? null : log.deliveredAt().toString());
    row.put("error_message", log.errorMessage());
    return row;
  }

  private static String blankToNull(String s) {
    return s == null || s.isBlank() ? null : s.trim();
  }
}
