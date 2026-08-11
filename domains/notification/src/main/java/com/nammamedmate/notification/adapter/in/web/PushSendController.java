package com.nammamedmate.notification.adapter.in.web;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.nammamedmate.kernel.api.ApiResponse;
import com.nammamedmate.notification.application.InternalPushAuth;
import com.nammamedmate.notification.application.PushSendService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/notifications/push")
@Tag(name = "Push notifications")
public class PushSendController {

  private final PushSendService push;
  private final InternalPushAuth internalAuth;

  public PushSendController(PushSendService push, InternalPushAuth internalAuth) {
    this.push = push;
    this.internalAuth = internalAuth;
  }

  @PostMapping("/send")
  @Operation(summary = "Send push notification (internal token)")
  public ApiResponse<Map<String, Object>> send(
      @RequestHeader(value = "X-Internal-Token", required = false) String internalToken,
      @RequestBody(required = false) SendRequest body) {
    internalAuth.require(internalToken);
    SendRequest req =
        body == null ? new SendRequest(null, null, null, null, null, null, null, null) : body;
    return ApiResponse.ok(
        push.send(
            new PushSendService.SendCommand(
                req.recipientType(),
                req.recipientIds(),
                req.title(),
                req.body(),
                req.data(),
                req.imageUrl(),
                req.actionUrl(),
                req.priority(),
                null)));
  }

  @PostMapping("/opened")
  @Operation(summary = "Mark push notification opened via deep-link click")
  public ApiResponse<Map<String, Object>> opened(
      @org.springframework.security.core.annotation.AuthenticationPrincipal
          com.nammamedmate.security.MedmatePrincipal principal,
      @RequestBody(required = false) OpenedRequest body) {
    OpenedRequest req = body == null ? new OpenedRequest(null) : body;
    return ApiResponse.ok(push.markOpened(req.logId(), principal.subject()));
  }

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record SendRequest(
      String recipientType,
      List<UUID> recipientIds,
      String title,
      String body,
      Map<String, Object> data,
      String imageUrl,
      String actionUrl,
      String priority) {
    public SendRequest {
      recipientIds = recipientIds == null ? null : List.copyOf(recipientIds);
      data =
          data == null
              ? null
              : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(data));
    }
  }

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record OpenedRequest(UUID logId) {}
}
