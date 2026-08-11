package com.nammamedmate.notification.application;

import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.notification.application.port.out.CommunicationChannelLookupPort;
import com.nammamedmate.notification.application.port.out.Msg91ClientPort;
import com.nammamedmate.notification.application.port.out.PreferenceGatePort;
import com.nammamedmate.notification.application.port.out.SmsDeliveryLogStore;
import com.nammamedmate.notification.application.port.out.SmsTemplateStore;
import com.nammamedmate.notification.application.port.out.TwilioClientPort;
import com.nammamedmate.notification.domain.SmsCategory;
import com.nammamedmate.notification.domain.SmsDeliveryLog;
import com.nammamedmate.notification.domain.SmsLogStatus;
import com.nammamedmate.notification.domain.SmsProvider;
import com.nammamedmate.notification.domain.SmsTemplate;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

@Service
public class SmsSendService {

  public static final String SENDER_ID = "NMMATE";
  static final ZoneId IST = ZoneId.of("Asia/Kolkata");
  static final LocalTime PROMO_BLOCK_START = LocalTime.of(21, 0);
  static final LocalTime PROMO_BLOCK_END = LocalTime.of(9, 0);
  private static final Pattern E164 = Pattern.compile("^\\+[1-9]\\d{7,14}$");

  private final SmsTemplateStore templates;
  private final SmsDeliveryLogStore logs;
  private final Msg91ClientPort msg91;
  private final TwilioClientPort twilio;
  private final PreferenceGatePort preferences;
  private final CommunicationChannelLookupPort channels;
  private final Clock clock;

  public SmsSendService(
      SmsTemplateStore templates,
      SmsDeliveryLogStore logs,
      Msg91ClientPort msg91,
      TwilioClientPort twilio,
      PreferenceGatePort preferences,
      CommunicationChannelLookupPort channels,
      Clock clock) {
    this.templates = templates;
    this.logs = logs;
    this.msg91 = msg91;
    this.twilio = twilio;
    this.preferences = preferences;
    this.channels = channels;
    this.clock = clock;
  }

  public record SendCommand(
      String toPhone, String templateId, Map<String, String> variables, String priority) {
    public SendCommand {
      variables =
          variables == null
              ? Map.of()
              : Collections.unmodifiableMap(new LinkedHashMap<>(variables));
    }
  }

  public Map<String, Object> send(SendCommand cmd) {
    String phone = cmd.toPhone() == null ? "" : cmd.toPhone().trim();
    if (!E164.matcher(phone).matches()) {
      throw new AppException("INVALID_PHONE_FORMAT", "Phone must be E.164 format", 400);
    }
    String templateId = cmd.templateId() == null ? "" : cmd.templateId().trim();
    SmsTemplate template =
        templates
            .findById(templateId)
            .orElseThrow(
                () -> new AppException("TEMPLATE_NOT_FOUND", "template_id not in database", 422));
    if (!template.active()) {
      throw new AppException("TEMPLATE_INACTIVE", "Template is deactivated", 422);
    }
    if (template.dltTemplateId() == null || template.dltTemplateId().isBlank()) {
      throw new AppException("DLT_TEMPLATE_MISSING", "Template has no dlt_template_id", 422);
    }

    if (template.category() == SmsCategory.PROMOTIONAL) {
      if (isPromotionalRestricted(clock.instant())) {
        throw new AppException(
            "PROMOTIONAL_TIME_RESTRICTED",
            "Promotional SMS blocked between 21:00 and 09:00 IST",
            422);
      }
      if (!preferences.allowsSms(phone, SmsCategory.PROMOTIONAL.name())) {
        throw new AppException("PREFERENCE_BLOCKED", "Recipient opted out of promotional SMS", 422);
      }
      if (msg91.isOnDnd(phone)) {
        UUID logId = Ids.newId();
        Instant now = clock.instant();
        logs.insert(
            new SmsDeliveryLog(
                logId,
                phone,
                template.templateId(),
                cmd.variables(),
                null,
                null,
                false,
                SmsLogStatus.SKIPPED_DND,
                null,
                now,
                null,
                null));
        return toResponse(
            logId, phone, template.templateId(), null, null, SmsLogStatus.SKIPPED_DND, null, now);
      }
    }

    if (channels.resolveActiveProvider("SMS").isEmpty()) {
      throw new AppException("ALL_PROVIDERS_FAILED", "SMS channel unavailable", 503);
    }

    String body = render(template.content(), cmd.variables());
    Instant now = clock.instant();

    Msg91ClientPort.SendResult primary =
        msg91.send(
            new Msg91ClientPort.SendRequest(
                phone,
                template.dltTemplateId(),
                SENDER_ID,
                body,
                cmd.variables(),
                template.category()));
    if (primary.success()) {
      return persistSuccess(
          phone,
          template.templateId(),
          cmd.variables(),
          SmsProvider.MSG91,
          primary.messageId(),
          false,
          now);
    }

    TwilioClientPort.SendResult fallback =
        twilio.send(new TwilioClientPort.SendRequest(phone, SENDER_ID, body, cmd.variables()));
    if (fallback.success()) {
      return persistSuccess(
          phone,
          template.templateId(),
          cmd.variables(),
          SmsProvider.TWILIO,
          fallback.messageId(),
          true,
          now);
    }

    UUID failId = Ids.newId();
    String err = "All providers failed";
    if (fallback.errorMessage() != null) {
      err = fallback.errorMessage();
    } else if (primary.errorMessage() != null) {
      err = primary.errorMessage();
    }
    logs.insert(
        new SmsDeliveryLog(
            failId,
            phone,
            template.templateId(),
            cmd.variables(),
            SmsProvider.TWILIO,
            null,
            true,
            SmsLogStatus.FAILED,
            null,
            now,
            null,
            err));
    throw new AppException("ALL_PROVIDERS_FAILED", "Both MSG91 and Twilio failed", 503);
  }

  public Map<String, Object> handleWebhook(String providerMessageId, Instant deliveredAt) {
    if (providerMessageId == null || providerMessageId.isBlank()) {
      throw new AppException("MISSING_MESSAGE_ID", "provider_message_id is required", 400);
    }
    Instant at = deliveredAt == null ? clock.instant() : deliveredAt;
    boolean ok = logs.markDelivered(providerMessageId.trim(), at);
    if (!ok) {
      throw new AppException("LOG_NOT_FOUND", "SMS log not found for provider message id", 404);
    }
    return Map.of("updated", true, "provider_message_id", providerMessageId.trim());
  }

  public BigDecimal monthlyCost(Instant fromInclusive, Instant toExclusive) {
    BigDecimal sum = logs.sumCostBetween(fromInclusive, toExclusive);
    return sum == null ? BigDecimal.ZERO : sum;
  }

  static boolean isPromotionalRestricted(Instant instant) {
    LocalTime local = instant.atZone(IST).toLocalTime();
    return !local.isBefore(PROMO_BLOCK_START) || local.isBefore(PROMO_BLOCK_END);
  }

  static String render(String content, Map<String, String> variables) {
    if (content == null) {
      return "";
    }
    String out = content;
    if (variables != null) {
      for (Map.Entry<String, String> e : variables.entrySet()) {
        out = out.replace("{{" + e.getKey() + "}}", e.getValue() == null ? "" : e.getValue());
      }
    }
    return out;
  }

  private Map<String, Object> persistSuccess(
      String phone,
      String templateId,
      Map<String, String> variables,
      SmsProvider provider,
      String messageId,
      boolean fallbackUsed,
      Instant now) {
    UUID logId = Ids.newId();
    BigDecimal cost = provider.costRs();
    logs.insert(
        new SmsDeliveryLog(
            logId,
            phone,
            templateId,
            variables,
            provider,
            messageId,
            fallbackUsed,
            SmsLogStatus.SENT,
            cost,
            now,
            null,
            null));
    return toResponse(logId, phone, templateId, provider, messageId, SmsLogStatus.SENT, cost, now);
  }

  private static Map<String, Object> toResponse(
      UUID logId,
      String phone,
      String templateId,
      SmsProvider provider,
      String messageId,
      SmsLogStatus status,
      BigDecimal cost,
      Instant sentAt) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("log_id", logId.toString());
    data.put("to_phone", phone);
    data.put("template_id", templateId);
    data.put("provider", provider == null ? null : provider.name());
    data.put("provider_message_id", messageId);
    data.put("status", status.name());
    data.put("cost_rs", cost);
    data.put("sent_at", sentAt.toString());
    data.put("fallback_used", provider == SmsProvider.TWILIO);
    return data;
  }
}
