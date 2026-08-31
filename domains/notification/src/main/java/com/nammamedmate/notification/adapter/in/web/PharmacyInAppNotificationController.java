package com.nammamedmate.notification.adapter.in.web;

import com.nammamedmate.kernel.api.ApiResponse;
import com.nammamedmate.kernel.api.PaginationMeta;
import com.nammamedmate.notification.application.PharmacyInAppNotificationService;
import com.nammamedmate.security.MedmatePrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/pharmacy/notifications")
@Tag(name = "Pharmacy notifications")
public class PharmacyInAppNotificationController {

  private final PharmacyInAppNotificationService notifications;

  public PharmacyInAppNotificationController(PharmacyInAppNotificationService notifications) {
    this.notifications = notifications;
  }

  @GetMapping("/count")
  @Operation(summary = "Unread pharmacy notice count")
  public ApiResponse<Map<String, Object>> count(
      @AuthenticationPrincipal MedmatePrincipal principal) {
    return ApiResponse.ok(notifications.unreadCount(principal));
  }

  @GetMapping
  @Operation(summary = "List pharmacy notices")
  public ApiResponse<Map<String, Object>> list(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @RequestParam(value = "unread_only", required = false) Boolean unreadOnly,
      @RequestParam(value = "page", required = false) Integer page,
      @RequestParam(value = "limit", required = false) Integer limit) {
    PharmacyInAppNotificationService.HistoryPage result =
        notifications.list(principal, unreadOnly, page, limit);
    return ApiResponse.ok(
        result.data(), PaginationMeta.of(result.page(), result.limit(), result.total()));
  }

  @PutMapping
  @Operation(summary = "Mark all pharmacy notices as read")
  public ApiResponse<Map<String, Object>> markAll(
      @AuthenticationPrincipal MedmatePrincipal principal) {
    return ApiResponse.ok(notifications.markAllRead(principal));
  }

  @PostMapping("/{id}/read")
  @Operation(summary = "Mark one pharmacy notice as read")
  public ApiResponse<Map<String, Object>> read(
      @AuthenticationPrincipal MedmatePrincipal principal, @PathVariable("id") UUID id) {
    return ApiResponse.ok(notifications.markRead(principal, id));
  }
}
