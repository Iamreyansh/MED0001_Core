package com.nammamedmate.notification.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.notification.application.port.out.MetaWhatsAppClientPort;
import com.nammamedmate.notification.application.port.out.PreferenceGatePort;
import com.nammamedmate.notification.application.port.out.WhatsAppDeliveryLogStore;
import com.nammamedmate.notification.application.port.out.WhatsAppOptoutStore;
import com.nammamedmate.notification.application.port.out.WhatsAppSessionStore;
import com.nammamedmate.notification.application.port.out.WhatsAppTemplateStore;
import com.nammamedmate.notification.domain.WhatsAppDeliveryLog;
import com.nammamedmate.notification.domain.WhatsAppLogStatus;
import com.nammamedmate.notification.domain.WhatsAppOptoutSource;
import com.nammamedmate.notification.domain.WhatsAppTemplate;
import com.nammamedmate.notification.domain.WhatsAppTemplateStatus;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

@Service
public class WhatsAppSendService {

  private static final Pattern E164 = Pattern.compile("^\\+[1-9]\\d{7,14}$");
  private static final Pattern BODY_PLACEHOLDER = Pattern.compile("\\{\\{\\d+\\}\\}");
  private static final Pattern LANGUAGE = Pattern.compile("^[a-z]{2}(_[A-Z]{2})?$");

  private final WhatsAppTemplateStore templates;
  private final WhatsAppDeliveryLogStore logs;
  private final WhatsAppOptoutStore optouts;
  private final WhatsAppSessionStore sessions;
  private final MetaWhatsAppClientPort meta;
  private final PreferenceGatePort preferences;
  private final ObjectMapper mapper;
  private final Clock clock;

  public WhatsAppSendService(
      WhatsAppTemplateStore templates,
      WhatsAppDeliveryLogStore logs,
      WhatsAppOptoutStore optouts,
      WhatsAppSessionStore sessions,
      MetaWhatsAppClientPort meta,
      PreferenceGatePort preferences,
      ObjectMapper mapper,
      Clock clock) {
    this.templates = templates;
    this.logs = logs;
    this.optouts = optouts;
    this.sessions = sessions;
    this.meta = meta;
    this.preferences = preferences;
    this.mapper = mapper;
    this.clock = clock;
  }

  public record SendCommand(
      String toPhone,
      String templateName,
      String templateLanguage,
      List<Map<String, Object>> components) {
    public SendCommand {
      components =
          components == null
              ? List.of()
              : Collections.unmodifiableList(new ArrayList<>(components));
    }
  }

  public Map<String, Object> send(SendCommand cmd) {
    String phone = cmd.toPhone() == null ? "" : cmd.toPhone().trim();
    if (!E164.matcher(phone).matches()) {
      throw new AppException("INVALID_PHONE_FORMAT", "Phone must be E.164 format", 400);
    }
    if (optouts.isActivelyOptedOut(phone) || !preferences.allowsWhatsApp(phone)) {
      throw new AppException("RECIPIENT_OPTED_OUT", "Customer has opted out from WhatsApp", 422);
    }

    String templateName = cmd.templateName() == null ? "" : cmd.templateName().trim();
    WhatsAppTemplate template =
        templates
            .findByName(templateName)
            .orElseThrow(
                () ->
                    new AppException(
                        "TEMPLATE_NOT_FOUND", "template_name not in approved templates", 422));
    if (template.status() != WhatsAppTemplateStatus.APPROVED) {
      throw new AppException(
          "TEMPLATE_NOT_APPROVED", "Template status is PENDING or REJECTED", 422);
    }

    String language =
        cmd.templateLanguage() == null || cmd.templateLanguage().isBlank()
            ? template.language()
            : cmd.templateLanguage().trim();
    if (!LANGUAGE.matcher(language).matches()) {
      throw new AppException("INVALID_LANGUAGE", "language code not supported", 422);
    }

    validateComponents(template, cmd.components());

    MetaWhatsAppClientPort.SendResult result =
        meta.sendTemplate(
            new MetaWhatsAppClientPort.SendRequest(
                phone, template.templateName(), language, cmd.components()));
    Instant now = clock.instant();
    if (!result.success()) {
      UUID failId = Ids.newId();
      logs.insert(
          new WhatsAppDeliveryLog(
              failId,
              phone,
              template.templateName(),
              cmd.components(),
              null,
              WhatsAppLogStatus.FAILED,
              null,
              now,
              null,
              null,
              "META_ERROR",
              result.errorMessage()));
      throw new AppException("META_API_UNAVAILABLE", "Meta Cloud API returned error", 503);
    }

    UUID logId = Ids.newId();
    BigDecimal cost = template.category().costRs();
    logs.insert(
        new WhatsAppDeliveryLog(
            logId,
            phone,
            template.templateName(),
            cmd.components(),
            result.waMessageId(),
            WhatsAppLogStatus.SENT,
            cost,
            now,
            null,
            null,
            null,
            null));
    templates.touchLastUsed(template.templateName(), now);

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("log_id", logId.toString());
    data.put("to_phone", phone);
    data.put("template_name", template.templateName());
    data.put("wa_message_id", result.waMessageId());
    data.put("status", WhatsAppLogStatus.SENT.name());
    data.put("sent_at", now.toString());
    return data;
  }

  public Map<String, Object> handleWebhook(String signatureHeader, byte[] rawBody) {
    if (!meta.verifyWebhookSignature(signatureHeader, rawBody)) {
      throw new AppException("INVALID_SIGNATURE", "X-Hub-Signature-256 verification failed", 403);
    }
    JsonNode root;
    try {
      byte[] payload = rawBody == null ? new byte[0] : rawBody;
      root = mapper.readTree(payload.length == 0 ? "{}".getBytes(StandardCharsets.UTF_8) : payload);
    } catch (Exception e) {
      return Map.of("processed", false);
    }

    JsonNode entry = root.path("entry");
    if (!entry.isArray()) {
      return Map.of("processed", true);
    }
    for (JsonNode e : entry) {
      JsonNode changes = e.path("changes");
      if (!changes.isArray()) {
        continue;
      }
      for (JsonNode change : changes) {
        JsonNode value = change.path("value");
        processStatuses(value.path("statuses"));
        processInboundMessages(value.path("messages"));
      }
    }
    return Map.of("processed", true);
  }

  private void processStatuses(JsonNode statuses) {
    if (!statuses.isArray()) {
      return;
    }
    for (JsonNode statusNode : statuses) {
      String waId = text(statusNode, "id");
      String status = text(statusNode, "status");
      if (waId == null || status == null) {
        continue;
      }
      Instant at = epochOrNow(text(statusNode, "timestamp"));
      switch (status.toLowerCase()) {
        case "delivered" -> logs.markDelivered(waId, at);
        case "read" -> {
          logs.markDelivered(waId, at);
          logs.markRead(waId, at);
        }
        case "failed" -> {
          String code = text(statusNode.path("errors").path(0), "code");
          String message = text(statusNode.path("errors").path(0), "title");
          logs.markFailed(waId, code, message);
        }
        default -> {
          // sent / ignored
        }
      }
    }
  }

  private void processInboundMessages(JsonNode messages) {
    if (!messages.isArray()) {
      return;
    }
    for (JsonNode msg : messages) {
      String from = text(msg, "from");
      if (from == null) {
        continue;
      }
      String phone = toE164(from);
      Instant at = epochOrNow(text(msg, "timestamp"));
      sessions.upsertCustomerMessage(phone, at);

      String body = text(msg.path("text"), "body");
      if (body == null) {
        continue;
      }
      String trimmed = body.trim();
      if ("STOP".equalsIgnoreCase(trimmed) || "0".equals(trimmed)) {
        optouts.upsertActive(Ids.newId(), phone, WhatsAppOptoutSource.WA_REPLY, at);
      }
    }
  }

  static void validateComponents(WhatsAppTemplate template, List<Map<String, Object>> components) {
    int expectedBodyParams = countPlaceholders(template.bodyText());
    int actualBodyParams = 0;
    if (components != null) {
      for (Map<String, Object> component : components) {
        if (component == null) {
          continue;
        }
        Object type = component.get("type");
        if (type == null || !"body".equalsIgnoreCase(String.valueOf(type))) {
          continue;
        }
        Object params = component.get("parameters");
        if (params instanceof List<?> list) {
          actualBodyParams = list.size();
        }
      }
    }
    if (expectedBodyParams > 0 && actualBodyParams != expectedBodyParams) {
      throw new AppException(
          "COMPONENT_MISMATCH", "Component parameters don't match template", 422);
    }
  }

  static int countPlaceholders(String body) {
    if (body == null || body.isBlank()) {
      return 0;
    }
    Matcher m = BODY_PLACEHOLDER.matcher(body);
    int n = 0;
    while (m.find()) {
      n++;
    }
    return n;
  }

  static String toE164(String raw) {
    if (raw == null || raw.isBlank()) {
      return "";
    }
    String trimmed = raw.trim();
    if (trimmed.startsWith("+")) {
      return trimmed;
    }
    return "+" + trimmed;
  }

  private Instant epochOrNow(String epochSeconds) {
    if (epochSeconds == null || epochSeconds.isBlank()) {
      return clock.instant();
    }
    try {
      return Instant.ofEpochSecond(Long.parseLong(epochSeconds.trim()));
    } catch (NumberFormatException e) {
      return clock.instant();
    }
  }

  /** Package-visible for branch coverage of timestamp parsing. */
  Instant epochOrNowForTest(String epochSeconds) {
    return epochOrNow(epochSeconds);
  }

  static String text(JsonNode node, String field) {
    if (node == null || node.isMissingNode() || node.isNull()) {
      return null;
    }
    JsonNode v = node.get(field);
    if (v == null || v.isNull()) {
      return null;
    }
    String s = v.asText();
    return s.isBlank() ? null : s;
  }

  /** Exposed for session window checks (future free-form send). */
  public boolean withinCustomerSession(String phone) {
    Optional<Instant> last = sessions.lastCustomerMessageAt(phone);
    if (last.isEmpty()) {
      return false;
    }
    return last.get().isAfter(clock.instant().minusSeconds(24 * 60 * 60));
  }
}
