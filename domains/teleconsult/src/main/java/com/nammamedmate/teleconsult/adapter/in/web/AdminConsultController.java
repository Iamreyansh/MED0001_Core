package com.nammamedmate.teleconsult.adapter.in.web;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.nammamedmate.kernel.api.ApiResponse;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.teleconsult.application.ConsultEPrescriptionService;
import com.nammamedmate.teleconsult.application.ConsultEPrescriptionService.IssueRequest;
import com.nammamedmate.teleconsult.application.ConsultSessionService;
import com.nammamedmate.teleconsult.application.ConsultSessionService.AdminListResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
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
@RequestMapping("/api/v1/admin/consults")
@Tag(name = "Admin teleconsult sessions")
public class AdminConsultController {

  private final ConsultSessionService service;
  private final ConsultEPrescriptionService ePrescriptionService;

  public AdminConsultController(
      ConsultSessionService service, ConsultEPrescriptionService ePrescriptionService) {
    this.service = service;
    this.ePrescriptionService = ePrescriptionService;
  }

  @PostMapping("/{id}/status")
  @Operation(summary = "Advance consult status (strict state machine)")
  public ApiResponse<Map<String, Object>> updateStatus(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @PathVariable UUID id,
      @RequestBody(required = false) StatusBody body) {
    StatusBody req = body == null ? new StatusBody(null, null, null, null) : body;
    return ApiResponse.ok(
        service.updateStatus(
            principal, id, req.status(), req.notes(), req.isAdviceOnly(), req.clinicalNotes()));
  }

  @GetMapping("/queue")
  @Operation(summary = "Active consult queue for ops dashboard")
  public ApiResponse<Map<String, Object>> queue(
      @AuthenticationPrincipal MedmatePrincipal principal) {
    return ApiResponse.ok(service.queue(principal));
  }

  @GetMapping
  @Operation(summary = "List admin consults for a date with day stats")
  public ApiResponse<Map<String, Object>> list(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @RequestParam(name = "date", required = false) String date,
      @RequestParam(name = "doctor_id", required = false) UUID doctorId,
      @RequestParam(name = "status", required = false) String status,
      @RequestParam(name = "is_cart_mode", required = false) Boolean isCartMode,
      @RequestParam(name = "page", required = false) Integer page,
      @RequestParam(name = "limit", required = false) Integer limit) {
    AdminListResult result =
        service.list(principal, date, doctorId, status, isCartMode, page, limit);
    return ApiResponse.ok(result.data(), result.meta());
  }

  @PostMapping("/{id}/eprescription")
  @Operation(summary = "Issue e-prescription for an assigned consult")
  public ResponseEntity<ApiResponse<Map<String, Object>>> issueEprescription(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @PathVariable UUID id,
      @RequestBody(required = false) EprescriptionBody body) {
    IssueRequest req =
        body == null
            ? new IssueRequest(null, false, null, null)
            : new IssueRequest(
                body.medicines(), body.adviceOnly(), body.adviceText(), body.clinicalNotes());
    Map<String, Object> data = ePrescriptionService.issue(principal, id, req);
    return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(data));
  }

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record StatusBody(
      String status, String notes, Boolean isAdviceOnly, String clinicalNotes) {}

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record EprescriptionBody(
      List<Map<String, Object>> medicines,
      Boolean adviceOnly,
      String adviceText,
      String clinicalNotes) {}
}
