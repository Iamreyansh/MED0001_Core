package com.nammamedmate.notification.adapter.in.web;

import com.nammamedmate.kernel.api.ApiResponse;
import com.nammamedmate.kernel.api.PaginationMeta;
import com.nammamedmate.notification.application.PushSendService;
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
@RequestMapping("/api/v1/admin/notifications/push/logs")
@Tag(name = "Admin push logs")
public class AdminPushLogController {

  private final PushSendService push;

  public AdminPushLogController(PushSendService push) {
    this.push = push;
  }

  @GetMapping
  @Operation(summary = "List push notification delivery logs")
  public ApiResponse<Map<String, Object>> list(
      @RequestParam(value = "recipient_type", required = false) String recipientType,
      @RequestParam(value = "status", required = false) String status,
      @RequestParam(value = "date_from", required = false)
          @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          Instant dateFrom,
      @RequestParam(value = "date_to", required = false)
          @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          Instant dateTo,
      @RequestParam(value = "page", required = false) Integer page,
      @RequestParam(value = "limit", required = false) Integer limit) {
    PushSendService.LogPage result =
        push.listLogs(recipientType, status, dateFrom, dateTo, page, limit);
    return ApiResponse.ok(
        result.data(), PaginationMeta.of(result.page(), result.limit(), result.total()));
  }
}
