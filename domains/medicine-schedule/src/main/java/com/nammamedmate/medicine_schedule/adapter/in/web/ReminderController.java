package com.nammamedmate.medicine_schedule.adapter.in.web;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.nammamedmate.kernel.api.ApiResponse;
import com.nammamedmate.medicine_schedule.application.DoseReminderService;
import com.nammamedmate.medicine_schedule.application.MedicineScheduleInternalAuth;
import com.nammamedmate.security.MedmatePrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/schedule/reminders")
@Tag(name = "Schedule reminders")
public class ReminderController {

  private final DoseReminderService service;
  private final MedicineScheduleInternalAuth internalAuth;

  public ReminderController(
      DoseReminderService service, MedicineScheduleInternalAuth internalAuth) {
    this.service = service;
    this.internalAuth = internalAuth;
  }

  @PostMapping("/bulk-schedule")
  @Operation(summary = "Bulk-schedule dose reminders for a customer (internal)")
  public ApiResponse<Map<String, Object>> bulkSchedule(
      @RequestHeader(value = "X-Internal-Token", required = false) String internalToken,
      @RequestBody(required = false) BulkScheduleRequest body) {
    internalAuth.require(internalToken);
    BulkScheduleRequest req = body == null ? new BulkScheduleRequest(null, null) : body;
    return ApiResponse.ok(service.bulkSchedule(req.customerId(), req.daysAhead()));
  }

  @GetMapping("/today")
  @Operation(summary = "Today's reminders grouped by reminder_time")
  public ApiResponse<Map<String, Object>> today(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @RequestParam(value = "member_id", required = false) UUID memberId) {
    return ApiResponse.ok(service.today(principal, memberId));
  }

  @GetMapping("/upcoming")
  @Operation(summary = "Upcoming reminders in the look-ahead window")
  public ApiResponse<Map<String, Object>> upcoming(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @RequestParam(value = "member_id", required = false) UUID memberId,
      @RequestParam(value = "hours_ahead", required = false) Integer hoursAhead) {
    return ApiResponse.ok(service.upcoming(principal, memberId, hoursAhead));
  }

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record BulkScheduleRequest(UUID customerId, Integer daysAhead) {}
}
