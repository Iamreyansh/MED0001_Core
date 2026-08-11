package com.nammamedmate.notification.adapter.in.web;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.nammamedmate.kernel.api.ApiResponse;
import com.nammamedmate.kernel.webhook.WebhookRawBodyFilter;
import com.nammamedmate.notification.application.InternalPushAuth;
import com.nammamedmate.notification.application.NotificationWebhookAuth;
import com.nammamedmate.notification.application.SmsSendService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/notifications/sms")
@Tag(name = "SMS notifications")
public class SmsSendController {

  private final SmsSendService sms;
  private final InternalPushAuth internalAuth;
  private final NotificationWebhookAuth webhookAuth;

  public SmsSendController(
      SmsSendService sms, InternalPushAuth internalAuth, NotificationWebhookAuth webhookAuth) {
    this.sms = sms;
    this.internalAuth = internalAuth;
    this.webhookAuth = webhookAuth;
  }

  @PostMapping("/send")
  @Operation(summary = "Send SMS (internal token)")
  public ApiResponse<Map<String, Object>> send(
      @RequestHeader(value = "X-Internal-Token", required = false) String internalToken,
      @RequestBody(required = false) SendRequest body) {
    internalAuth.require(internalToken);
    SendRequest req = body == null ? new SendRequest(null, null, null, null) : body;
    return ApiResponse.ok(
        sms.send(
            new SmsSendService.SendCommand(
                req.toPhone(), req.templateId(), req.variables(), req.priority())));
  }

  @PostMapping("/webhook")
  @Operation(summary = "MSG91 delivery webhook — updates delivered_at")
  public ApiResponse<Map<String, Object>> webhook(
      HttpServletRequest request,
      @RequestHeader(value = "X-Msg91-Signature", required = false) String signature,
      @RequestBody(required = false) WebhookRequest body) {
    webhookAuth.requireSms(signature, WebhookRawBodyFilter.rawBody(request));
    WebhookRequest req = body == null ? new WebhookRequest(null, null, null, null) : body;
    String messageId = firstNonBlank(req.providerMessageId(), req.requestId(), req.messageId());
    return ApiResponse.ok(sms.handleWebhook(messageId, req.deliveredAt()));
  }

  private static String firstNonBlank(String... values) {
    for (String v : values) {
      if (v != null && !v.isBlank()) {
        return v;
      }
    }
    return null;
  }

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record SendRequest(
      String toPhone, String templateId, Map<String, String> variables, String priority) {
    public SendRequest {
      variables =
          variables == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(variables));
    }
  }

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record WebhookRequest(
      String providerMessageId, String requestId, String messageId, Instant deliveredAt) {}
}
