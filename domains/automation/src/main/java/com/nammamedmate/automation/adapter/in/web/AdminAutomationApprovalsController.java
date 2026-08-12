package com.nammamedmate.automation.adapter.in.web;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.nammamedmate.automation.application.ApprovalQueueService;
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
@RequestMapping("/api/v1/admin/automation/approvals")
@Tag(name = "Admin automation approvals")
public class AdminAutomationApprovalsController {

  private final ApprovalQueueService approvals;

  public AdminAutomationApprovalsController(ApprovalQueueService approvals) {
    this.approvals = approvals;
  }

  @GetMapping
  @Operation(summary = "List automation approvals queue")
  public ApiResponse<Map<String, Object>> list(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String urgency,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer limit) {
    ApprovalQueueService.PagedResult result =
        approvals.list(principal, status, urgency, page, limit);
    return ApiResponse.ok(result.data(), result.meta());
  }

  @GetMapping("/stats")
  @Operation(summary = "Approval queue performance statistics")
  public ApiResponse<Map<String, Object>> stats(
      @AuthenticationPrincipal MedmatePrincipal principal) {
    return ApiResponse.ok(approvals.stats(principal));
  }

  @GetMapping("/{id}")
  @Operation(summary = "Approval detail with trigger context")
  public ApiResponse<Map<String, Object>> get(
      @AuthenticationPrincipal MedmatePrincipal principal, @PathVariable("id") UUID id) {
    return ApiResponse.ok(approvals.get(principal, id));
  }

  @PostMapping("/{id}/approve")
  @Operation(summary = "Approve and execute the proposed action")
  public ApiResponse<Map<String, Object>> approve(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @PathVariable("id") UUID id,
      @RequestBody(required = false) ApproveRequest body) {
    ApproveRequest req = body == null ? new ApproveRequest(null) : body;
    return ApiResponse.ok(approvals.approve(principal, id, req.notes()));
  }

  @PostMapping("/{id}/reject")
  @Operation(summary = "Reject the approval (optional alternative action)")
  public ApiResponse<Map<String, Object>> reject(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @PathVariable("id") UUID id,
      @RequestBody(required = false) RejectRequest body) {
    RejectRequest req = body == null ? new RejectRequest(null) : body;
    return ApiResponse.ok(approvals.reject(principal, id, req.reason()));
  }

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record ApproveRequest(String notes) {}

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record RejectRequest(String reason) {}
}
