package com.nammamedmate.automation.adapter.in.web;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.nammamedmate.automation.application.ActivityLogService;
import com.nammamedmate.kernel.api.ApiResponse;
import com.nammamedmate.security.MedmatePrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/automation/activity")
@Tag(name = "Admin automation activity")
public class AdminAutomationActivityController {

  private final ActivityLogService activity;

  public AdminAutomationActivityController(ActivityLogService activity) {
    this.activity = activity;
  }

  @GetMapping
  @Operation(summary = "Paginated automation activity feed")
  public ApiResponse<Map<String, Object>> list(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @RequestParam(required = false) String status,
      @RequestParam(name = "rule_id", required = false) UUID ruleId,
      @RequestParam(name = "trigger_category", required = false) String triggerCategory,
      @RequestParam(name = "entity_type", required = false) String entityType,
      @RequestParam(name = "date_from", required = false) String dateFrom,
      @RequestParam(name = "date_to", required = false) String dateTo,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer limit) {
    ActivityLogService.PagedResult result =
        activity.list(
            principal, status, ruleId, triggerCategory, entityType, dateFrom, dateTo, page, limit);
    return ApiResponse.ok(result.data(), result.meta());
  }

  @GetMapping("/stats")
  @Operation(summary = "Automation health statistics")
  public ApiResponse<Map<String, Object>> stats(
      @AuthenticationPrincipal MedmatePrincipal principal) {
    return ApiResponse.ok(activity.stats(principal));
  }

  @GetMapping("/{actionId}")
  @Operation(summary = "Activity log detail including before/after state")
  public ApiResponse<Map<String, Object>> get(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @PathVariable("actionId") UUID actionId) {
    return ApiResponse.ok(activity.get(principal, actionId));
  }

  @PostMapping("/{actionId}/rollback")
  @Operation(summary = "Rollback a reversible automated action")
  public ApiResponse<Map<String, Object>> rollback(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @PathVariable("actionId") UUID actionId,
      @RequestBody(required = false) RollbackRequest body) {
    RollbackRequest req = body == null ? new RollbackRequest(null) : body;
    return ApiResponse.ok(activity.rollback(principal, actionId, req.reason()));
  }

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record RollbackRequest(String reason) {}
}
