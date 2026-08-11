package com.nammamedmate.notification.adapter.in.web;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.nammamedmate.kernel.api.ApiResponse;
import com.nammamedmate.kernel.webhook.WebhookRawBodyFilter;
import com.nammamedmate.notification.application.InternalPushAuth;
import com.nammamedmate.notification.application.WhatsAppSendService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/notifications/whatsapp")
@Tag(name = "WhatsApp notifications")
public class WhatsAppSendController {

  private final WhatsAppSendService whatsapp;
  private final InternalPushAuth internalAuth;

  public WhatsAppSendController(WhatsAppSendService whatsapp, InternalPushAuth internalAuth) {
    this.whatsapp = whatsapp;
    this.internalAuth = internalAuth;
  }

  @PostMapping("/send")
  @Operation(summary = "Send WhatsApp template message (internal token)")
  public ApiResponse<Map<String, Object>> send(
      @RequestHeader(value = "X-Internal-Token", required = false) String internalToken,
      @RequestBody(required = false) SendRequest body) {
    internalAuth.require(internalToken);
    SendRequest req = body == null ? new SendRequest(null, null, null, null) : body;
    return ApiResponse.ok(
        whatsapp.send(
            new WhatsAppSendService.SendCommand(
                req.toPhone(), req.templateName(), req.templateLanguage(), req.components())));
  }

  @PostMapping("/webhook")
  @Operation(summary = "Meta WhatsApp webhook — delivery receipts and STOP opt-out")
  public ApiResponse<Map<String, Object>> webhook(
      @RequestHeader(value = "X-Hub-Signature-256", required = false) String signature,
      HttpServletRequest request) {
    return ApiResponse.ok(whatsapp.handleWebhook(signature, WebhookRawBodyFilter.rawBody(request)));
  }

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record SendRequest(
      String toPhone,
      String templateName,
      String templateLanguage,
      List<Map<String, Object>> components) {
    public SendRequest {
      components =
          components == null
              ? null
              : Collections.unmodifiableList(
                  components.stream()
                      .map(
                          c ->
                              c == null
                                  ? Map.<String, Object>of()
                                  : Collections.unmodifiableMap(new LinkedHashMap<>(c)))
                      .toList());
    }
  }
}
