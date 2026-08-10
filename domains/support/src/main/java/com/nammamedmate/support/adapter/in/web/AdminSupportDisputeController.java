package com.nammamedmate.support.adapter.in.web;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.nammamedmate.kernel.api.ApiResponse;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.support.application.DisputeService;
import com.nammamedmate.support.application.DisputeService.ListResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/support/disputes")
@Tag(name = "Admin support disputes")
public class AdminSupportDisputeController {

  private final DisputeService disputes;

  public AdminSupportDisputeController(DisputeService disputes) {
    this.disputes = disputes;
  }

  @GetMapping
  @Operation(summary = "List disputes with chips; export=true returns CSV")
  public ResponseEntity<?> list(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @RequestParam(required = false) String status,
      @RequestParam(name = "liable_party", required = false) String liableParty,
      @RequestParam(name = "dispute_type", required = false) String disputeType,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer limit,
      @RequestParam(required = false) Boolean export) {
    ListResult result =
        disputes.listAdmin(principal, status, liableParty, disputeType, page, limit, export);
    if (Boolean.TRUE.equals(export)) {
      byte[] csv = disputes.exportCsvBytes(result);
      return ResponseEntity.ok()
          .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"support-disputes.csv\"")
          .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
          .body(csv);
    }
    if (result.meta() == null) {
      return ResponseEntity.ok(ApiResponse.ok(result.data()));
    }
    return ResponseEntity.ok(ApiResponse.ok(result.data(), result.meta()));
  }

  @GetMapping("/{id}")
  @Operation(summary = "Get dispute detail with history")
  public ApiResponse<Map<String, Object>> get(
      @AuthenticationPrincipal MedmatePrincipal principal, @PathVariable("id") UUID id) {
    return ApiResponse.ok(disputes.getAdmin(principal, id));
  }

  @PostMapping("/{id}/investigate")
  @Operation(summary = "Mark dispute as investigating")
  public ApiResponse<Map<String, Object>> investigate(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @PathVariable("id") UUID id,
      @RequestBody(required = false) InvestigateRequest body) {
    InvestigateRequest req = body == null ? new InvestigateRequest(null, null) : body;
    return ApiResponse.ok(
        disputes.investigate(
            principal, id, new DisputeService.InvestigateCommand(req.assignedTo(), req.notes())));
  }

  @PostMapping("/{id}/resolve-approve")
  @Operation(summary = "Approve dispute and refund")
  public ApiResponse<Map<String, Object>> resolveApprove(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @PathVariable("id") UUID id,
      @RequestBody(required = false) ApproveRequest body) {
    ApproveRequest req = body == null ? new ApproveRequest(null, null, null, null) : body;
    return ApiResponse.ok(
        disputes.resolveApprove(
            principal,
            id,
            new DisputeService.ApproveCommand(
                req.liableParty(), req.refundAmount(), req.refundTo(), req.resolutionNotes())));
  }

  @PostMapping("/{id}/resolve-reject")
  @Operation(summary = "Reject dispute")
  public ApiResponse<Map<String, Object>> resolveReject(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @PathVariable("id") UUID id,
      @RequestBody(required = false) RejectRequest body) {
    RejectRequest req = body == null ? new RejectRequest(null, null) : body;
    return ApiResponse.ok(
        disputes.resolveReject(
            principal, id, new DisputeService.RejectCommand(req.rejectionReason(), req.notes())));
  }

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record InvestigateRequest(UUID assignedTo, String notes) {}

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record ApproveRequest(
      String liableParty, Number refundAmount, String refundTo, String resolutionNotes) {}

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record RejectRequest(String rejectionReason, String notes) {}
}
