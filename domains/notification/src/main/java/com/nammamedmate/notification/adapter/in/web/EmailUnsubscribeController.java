package com.nammamedmate.notification.adapter.in.web;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.nammamedmate.kernel.api.ApiResponse;
import com.nammamedmate.notification.application.EmailUnsubscribeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Thin public unsubscribe (STORY-004 AC-004). STORY-005 expands preference toggles. */
@RestController
@RequestMapping("/api/v1/notifications")
@Tag(name = "Email unsubscribe")
public class EmailUnsubscribeController {

  private final EmailUnsubscribeService unsubscribe;

  public EmailUnsubscribeController(EmailUnsubscribeService unsubscribe) {
    this.unsubscribe = unsubscribe;
  }

  @PostMapping("/unsubscribe")
  @Operation(summary = "One-click email unsubscribe (signed token)")
  public ApiResponse<Map<String, Object>> unsubscribe(
      @RequestParam(value = "token", required = false) String tokenQuery,
      @RequestBody(required = false) UnsubscribeRequest body) {
    String token = tokenQuery;
    if ((token == null || token.isBlank()) && body != null) {
      token = body.token();
    }
    return ApiResponse.ok(unsubscribe.unsubscribe(token));
  }

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record UnsubscribeRequest(String token) {}
}
