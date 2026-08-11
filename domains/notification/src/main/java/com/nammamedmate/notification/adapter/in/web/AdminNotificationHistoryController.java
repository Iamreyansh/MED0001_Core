package com.nammamedmate.notification.adapter.in.web;

import com.nammamedmate.kernel.api.ApiResponse;
import com.nammamedmate.kernel.api.PaginationMeta;
import com.nammamedmate.notification.application.InAppNotificationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.util.Map;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/notifications/history")
@Tag(name = "Admin notification history")
public class AdminNotificationHistoryController {

  private final InAppNotificationService notifications;

  public AdminNotificationHistoryController(InAppNotificationService notifications) {
    this.notifications = notifications;
  }

  @GetMapping
  @Operation(summary = "Cross-channel notification dispatch history")
  public ApiResponse<Map<String, Object>> history(
      @RequestParam(value = "channel", required = false) String channel,
      @RequestParam(value = "status", required = false) String status,
      @RequestParam(value = "recipient_type", required = false) String recipientType,
      @RequestParam(value = "date_from", required = false)
          @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          Instant dateFrom,
      @RequestParam(value = "date_to", required = false)
          @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          Instant dateTo,
      @RequestParam(value = "export", required = false) String export,
      @RequestParam(value = "page", required = false) Integer page,
      @RequestParam(value = "limit", required = false) Integer limit) {
    InAppNotificationService.HistoryPage result =
        notifications.adminHistory(
            channel, status, recipientType, dateFrom, dateTo, export, page, limit);
    return ApiResponse.ok(
        result.data(), PaginationMeta.of(result.page(), result.limit(), result.total()));
  }
}
