package com.nammamedmate.notification.adapter.in.web;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.nammamedmate.kernel.api.ApiResponse;
import com.nammamedmate.kernel.api.PaginationMeta;
import com.nammamedmate.notification.application.InAppNotificationService;
import com.nammamedmate.security.MedmatePrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/customers/me/notifications")
@Tag(name = "Customer in-app notifications")
public class CustomerInAppNotificationController {

  private final InAppNotificationService notifications;

  public CustomerInAppNotificationController(InAppNotificationService notifications) {
    this.notifications = notifications;
  }

  @GetMapping("/count")
  @Operation(summary = "Unread in-app notification badge count")
  public ApiResponse<Map<String, Object>> count(
      @AuthenticationPrincipal MedmatePrincipal principal) {
    return ApiResponse.ok(notifications.unreadCount(principal.subject()));
  }

  @GetMapping
  @Operation(summary = "List in-app notifications for authenticated customer")
  public ApiResponse<Map<String, Object>> list(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @RequestParam(value = "unread_only", required = false) Boolean unreadOnly,
      @RequestParam(value = "type", required = false) String type,
      @RequestParam(value = "page", required = false) Integer page,
      @RequestParam(value = "limit", required = false) Integer limit) {
    InAppNotificationService.HistoryPage result =
        notifications.list(principal.subject(), unreadOnly, type, page, limit);
    return ApiResponse.ok(
        result.data(), PaginationMeta.of(result.page(), result.limit(), result.total()));
  }

  @PutMapping
  @Operation(summary = "Mark all in-app notifications as read")
  public ApiResponse<Map<String, Object>> markAll(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @RequestBody(required = false) MarkAllRequest body) {
    MarkAllRequest req = body == null ? new MarkAllRequest(null) : body;
    return ApiResponse.ok(notifications.markAllRead(principal.subject(), req.markAllRead()));
  }

  @PostMapping("/{id}/read")
  @Operation(summary = "Mark a single in-app notification as read")
  public ApiResponse<Map<String, Object>> read(
      @AuthenticationPrincipal MedmatePrincipal principal, @PathVariable("id") UUID id) {
    return ApiResponse.ok(notifications.markRead(principal.subject(), id));
  }

  @DeleteMapping("/{id}")
  @Operation(summary = "Soft-delete a PROMO or SYSTEM in-app notification")
  public ApiResponse<Map<String, Object>> delete(
      @AuthenticationPrincipal MedmatePrincipal principal, @PathVariable("id") UUID id) {
    return ApiResponse.ok(notifications.delete(principal.subject(), id));
  }

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record MarkAllRequest(Boolean markAllRead) {}
}
