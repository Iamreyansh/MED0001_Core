package com.nammamedmate.notification.adapter.in.web;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.nammamedmate.kernel.api.ApiResponse;
import com.nammamedmate.notification.application.BroadcastService;
import com.nammamedmate.security.MedmatePrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/notifications/broadcast")
@Tag(name = "Admin push broadcast")
public class AdminBroadcastController {

  private final BroadcastService broadcasts;

  public AdminBroadcastController(BroadcastService broadcasts) {
    this.broadcasts = broadcasts;
  }

  @PostMapping
  @Operation(summary = "Queue a push broadcast to an audience")
  public ResponseEntity<ApiResponse<Map<String, Object>>> create(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @RequestBody(required = false) BroadcastRequest body) {
    BroadcastRequest req = body == null ? new BroadcastRequest(null, null, null, null, null) : body;
    Map<String, Object> data =
        broadcasts.enqueue(
            principal.subject(),
            req.audience(),
            req.title(),
            req.body(),
            req.data(),
            req.scheduleAt());
    return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResponse.ok(data));
  }

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record BroadcastRequest(
      String audience, String title, String body, Map<String, Object> data, Instant scheduleAt) {
    public BroadcastRequest {
      data =
          data == null
              ? null
              : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(data));
    }
  }
}
