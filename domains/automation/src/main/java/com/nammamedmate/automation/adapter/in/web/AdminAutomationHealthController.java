package com.nammamedmate.automation.adapter.in.web;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.nammamedmate.automation.application.AutomationHealthService;
import com.nammamedmate.kernel.api.ApiResponse;
import com.nammamedmate.security.MedmatePrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/automation")
@Tag(name = "Admin automation health")
public class AdminAutomationHealthController {

  private final AutomationHealthService health;

  public AdminAutomationHealthController(AutomationHealthService health) {
    this.health = health;
  }

  @GetMapping("/health")
  @Operation(summary = "Automation engine health dashboard")
  public ApiResponse<Map<String, Object>> dashboard(
      @AuthenticationPrincipal MedmatePrincipal principal) {
    return ApiResponse.ok(health.dashboard(principal));
  }

  @GetMapping("/health/per-rule")
  @Operation(summary = "Per-rule health metrics (last 24h)")
  public ApiResponse<Map<String, Object>> perRule(
      @AuthenticationPrincipal MedmatePrincipal principal) {
    return ApiResponse.ok(health.perRule(principal));
  }

  @GetMapping("/health/circuit-breakers")
  @Operation(summary = "Circuit breaker status per action type")
  public ApiResponse<Map<String, Object>> circuitBreakers(
      @AuthenticationPrincipal MedmatePrincipal principal) {
    return ApiResponse.ok(health.circuitBreakers(principal));
  }

  @PostMapping("/kill-switch")
  @Operation(summary = "Pause or resume all automation globally")
  public ApiResponse<Map<String, Object>> killSwitch(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @RequestBody(required = false) KillSwitchRequest body) {
    KillSwitchRequest req = body == null ? new KillSwitchRequest(null, null) : body;
    return ApiResponse.ok(health.toggle(principal, req.action(), req.reason()));
  }

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record KillSwitchRequest(String action, String reason) {}
}
