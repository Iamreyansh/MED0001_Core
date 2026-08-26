package com.nammamedmate.notification.adapter.in.web;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.nammamedmate.kernel.api.ApiResponse;
import com.nammamedmate.kernel.webhook.WebhookRawBodyFilter;
import com.nammamedmate.notification.application.EmailSendService;
import com.nammamedmate.notification.application.InternalPushAuth;
import com.nammamedmate.notification.application.NotificationWebhookAuth;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/notifications/email")
@Tag(name = "Email notifications")
public class EmailSendController {

  /** 1x1 transparent GIF. */
  private static final byte[] PIXEL_GIF =
      new byte[] {
        0x47,
        0x49,
        0x46,
        0x38,
        0x39,
        0x61,
        0x01,
        0x00,
        0x01,
        0x00,
        (byte) 0x80,
        0x00,
        0x00,
        (byte) 0xff,
        (byte) 0xff,
        (byte) 0xff,
        0x00,
        0x00,
        0x00,
        0x21,
        (byte) 0xf9,
        0x04,
        0x01,
        0x00,
        0x00,
        0x00,
        0x00,
        0x2c,
        0x00,
        0x00,
        0x00,
        0x00,
        0x01,
        0x00,
        0x01,
        0x00,
        0x00,
        0x02,
        0x02,
        0x44,
        0x01,
        0x00,
        0x3b
      };

  private final EmailSendService email;
  private final InternalPushAuth internalAuth;
  private final NotificationWebhookAuth webhookAuth;

  public EmailSendController(
      EmailSendService email, InternalPushAuth internalAuth, NotificationWebhookAuth webhookAuth) {
    this.email = email;
    this.internalAuth = internalAuth;
    this.webhookAuth = webhookAuth;
  }

  @PostMapping("/send")
  @Operation(summary = "Send email (internal token)")
  public ApiResponse<Map<String, Object>> send(
      @RequestHeader(value = "X-Internal-Token", required = false) String internalToken,
      @RequestBody(required = false) SendRequest body) {
    internalAuth.require(internalToken);
    SendRequest req = body == null ? new SendRequest(null, null, null, null, null, null) : body;
    List<EmailSendService.AttachmentRef> attachments = List.of();
    if (req.attachments() != null) {
      attachments =
          req.attachments().stream()
              .map(a -> new EmailSendService.AttachmentRef(a.filename(), a.url()))
              .toList();
    }
    return ApiResponse.ok(
        email.send(
            new EmailSendService.SendCommand(
                req.toEmail(),
                req.toName(),
                req.templateId(),
                req.variables(),
                attachments,
                req.customerId())));
  }

  @PostMapping("/webhook")
  @Operation(summary = "SendGrid event webhook (bounce/spam/delivery)")
  public ApiResponse<Map<String, Object>> webhook(
      HttpServletRequest request,
      @RequestBody(required = false) Object body,
      @RequestHeader(value = "X-Twilio-Email-Event-Webhook-Signature", required = false)
          String signature) {
    webhookAuth.requireEmail(signature, WebhookRawBodyFilter.rawBody(request));
    List<Map<String, Object>> events = normalizeEvents(body);
    return ApiResponse.ok(email.handleWebhook(events));
  }

  @GetMapping(value = "/t/o/{logId}", produces = MediaType.IMAGE_GIF_VALUE)
  @Operation(summary = "Open tracking pixel")
  public ResponseEntity<byte[]> openPixel(@PathVariable("logId") UUID logId) {
    email.trackOpen(logId);
    return ResponseEntity.ok()
        .header(HttpHeaders.CACHE_CONTROL, "no-store, no-cache, must-revalidate")
        .contentType(MediaType.IMAGE_GIF)
        .body(PIXEL_GIF);
  }

  @GetMapping("/t/c/{logId}")
  @Operation(summary = "Click tracking redirect")
  public ResponseEntity<Void> clickRedirect(
      @PathVariable("logId") UUID logId, @RequestParam("u") String targetUrl) {
    Map<String, Object> tracked = email.trackClick(logId, targetUrl);
    return ResponseEntity.status(HttpStatus.FOUND)
        .location(URI.create(String.valueOf(tracked.get("redirect_url"))))
        .build();
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> normalizeEvents(Object body) {
    if (body == null) {
      return List.of();
    }
    if (body instanceof List<?> list) {
      return list.stream().filter(Map.class::isInstance).map(e -> (Map<String, Object>) e).toList();
    }
    if (body instanceof Map<?, ?> map) {
      Object events = map.get("events");
      if (events instanceof List<?> list) {
        return list.stream()
            .filter(Map.class::isInstance)
            .map(e -> (Map<String, Object>) e)
            .toList();
      }
      return List.of((Map<String, Object>) map);
    }
    return List.of();
  }

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record AttachmentRequest(String filename, String url) {}

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record SendRequest(
      String toEmail,
      String toName,
      String templateId,
      Map<String, Object> variables,
      List<AttachmentRequest> attachments,
      UUID customerId) {
    public SendRequest {
      variables =
          variables == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(variables));
      attachments = attachments == null ? null : List.copyOf(attachments);
    }
  }
}
