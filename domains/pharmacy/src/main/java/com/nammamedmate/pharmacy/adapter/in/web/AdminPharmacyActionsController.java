package com.nammamedmate.pharmacy.adapter.in.web;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.nammamedmate.kernel.api.ApiResponse;
import com.nammamedmate.pharmacy.application.AdminPharmacyActionsService;
import com.nammamedmate.pharmacy.application.AdminPharmacyActionsService.NotesListResult;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.RequiresPermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/pharmacies")
@Tag(name = "Admin pharmacy actions")
public class AdminPharmacyActionsController {

  private final AdminPharmacyActionsService service;

  public AdminPharmacyActionsController(AdminPharmacyActionsService service) {
    this.service = service;
  }

  @PostMapping("/{id}/notice")
  @RequiresPermission("pharmacies:read")
  @Operation(summary = "Admin: send notice to pharmacy")
  public ApiResponse<Map<String, Object>> sendNotice(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @PathVariable("id") UUID id,
      @RequestBody(required = false) NoticeRequest body) {
    NoticeRequest req = body == null ? new NoticeRequest(null, null, null, null, null) : body;
    return ApiResponse.ok(
        service.sendNotice(
            principal,
            id,
            req.channel(),
            req.subject(),
            req.message(),
            req.priority(),
            req.templateName()));
  }

  @PostMapping("/{id}/notes")
  @RequiresPermission("pharmacies:read")
  @ResponseStatus(HttpStatus.CREATED)
  @Operation(summary = "Admin: add internal note")
  public ApiResponse<Map<String, Object>> addNote(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @PathVariable("id") UUID id,
      @RequestBody(required = false) AddNoteRequest body) {
    AddNoteRequest req = body == null ? new AddNoteRequest(null, null) : body;
    return ApiResponse.ok(service.addNote(principal, id, req.note(), req.isFlagged()));
  }

  @GetMapping("/{id}/notes")
  @RequiresPermission("pharmacies:read")
  @Operation(summary = "Admin: list internal notes")
  public ApiResponse<Map<String, Object>> listNotes(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @PathVariable("id") UUID id,
      @RequestParam(name = "is_flagged", required = false) Boolean flaggedOnly,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer limit) {
    NotesListResult result = service.listNotes(principal, id, flaggedOnly, page, limit);
    return ApiResponse.ok(result.data(), result.meta());
  }

  @PostMapping("/{id}/call-log")
  @RequiresPermission("pharmacies:read")
  @ResponseStatus(HttpStatus.CREATED)
  @Operation(summary = "Admin: log phone call to pharmacy")
  public ApiResponse<Map<String, Object>> logCall(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @PathVariable("id") UUID id,
      @RequestBody(required = false) CallLogRequest body) {
    CallLogRequest req = body == null ? new CallLogRequest(null, null, null) : body;
    return ApiResponse.ok(
        service.logCall(principal, id, req.durationSeconds(), req.callOutcome(), req.notes()));
  }

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record NoticeRequest(
      String channel, String subject, String message, String priority, String templateName) {}

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record AddNoteRequest(String note, Boolean isFlagged) {}

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record CallLogRequest(Integer durationSeconds, String callOutcome, String notes) {}
}
