package com.nammamedmate.medicine_schedule.adapter.in.web;

import com.nammamedmate.kernel.api.ApiResponse;
import com.nammamedmate.medicine_schedule.application.AdherenceService;
import com.nammamedmate.security.MedmatePrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/schedule/adherence")
@Tag(name = "Schedule adherence")
public class AdherenceController {

  private final AdherenceService service;

  public AdherenceController(AdherenceService service) {
    this.service = service;
  }

  @GetMapping
  @Operation(summary = "Adherence summary for a care circle member")
  public ApiResponse<Map<String, Object>> summary(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @RequestParam(value = "member_id", required = false) UUID memberId) {
    return ApiResponse.ok(service.summary(principal, memberId));
  }

  @GetMapping("/calendar")
  @Operation(summary = "Monthly adherence calendar heatmap")
  public ApiResponse<Map<String, Object>> calendar(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @RequestParam(value = "member_id", required = false) UUID memberId,
      @RequestParam(value = "month", required = false) String month) {
    return ApiResponse.ok(service.calendar(principal, memberId, month));
  }

  @GetMapping("/chart")
  @Operation(summary = "Weekly adherence chart series")
  public ApiResponse<Map<String, Object>> chart(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @RequestParam(value = "member_id", required = false) UUID memberId,
      @RequestParam(value = "weeks", required = false) Integer weeks) {
    return ApiResponse.ok(service.chart(principal, memberId, weeks));
  }
}
