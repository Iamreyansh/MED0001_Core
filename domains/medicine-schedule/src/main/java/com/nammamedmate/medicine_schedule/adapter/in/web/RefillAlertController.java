package com.nammamedmate.medicine_schedule.adapter.in.web;

import com.nammamedmate.kernel.api.ApiResponse;
import com.nammamedmate.medicine_schedule.application.RefillAlertService;
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
@RequestMapping("/api/v1/schedule/refill-alerts")
@Tag(name = "Refill alerts")
public class RefillAlertController {

  private final RefillAlertService service;

  public RefillAlertController(RefillAlertService service) {
    this.service = service;
  }

  @GetMapping
  @Operation(summary = "List medicines currently in refill alert")
  public ApiResponse<Map<String, Object>> list(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @RequestParam(value = "member_id", required = false) UUID memberId) {
    return ApiResponse.ok(service.listAlerts(principal, memberId));
  }

  @GetMapping("/share")
  @Operation(summary = "Generate a 30-day shareable schedule link")
  public ApiResponse<Map<String, Object>> share(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @RequestParam(value = "member_id", required = false) UUID memberId) {
    return ApiResponse.ok(service.createShareLink(principal, memberId));
  }
}
