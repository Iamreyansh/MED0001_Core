package com.nammamedmate.auth.adapter.in.web;

import com.nammamedmate.auth.adapter.in.web.dto.InvitePharmacyStaffRequest;
import com.nammamedmate.auth.adapter.in.web.dto.SetPharmacyPosPinRequest;
import com.nammamedmate.auth.application.PharmacyStaffService;
import com.nammamedmate.auth.application.PharmacyStaffService.StaffListResult;
import com.nammamedmate.kernel.api.ApiResponse;
import com.nammamedmate.security.MedmatePrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/pharmacy/staff")
@Tag(name = "Pharmacy staff")
public class PharmacyStaffController {

  private final PharmacyStaffService staffService;

  public PharmacyStaffController(PharmacyStaffService staffService) {
    this.staffService = staffService;
  }

  @GetMapping
  @Operation(summary = "List staff assigned to the active pharmacy")
  public ApiResponse<List<Map<String, Object>>> list(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @RequestParam(name = "status", required = false) String status,
      @RequestParam(name = "q", required = false) String q,
      @RequestParam(name = "page", required = false) Integer page,
      @RequestParam(name = "limit", required = false) Integer limit) {
    StaffListResult result = staffService.list(principal, status, q, page, limit);
    return ApiResponse.ok(result.data(), result.meta());
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  @Operation(summary = "Invite staff to the active pharmacy")
  public ApiResponse<Map<String, Object>> invite(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @RequestBody InvitePharmacyStaffRequest request) {
    InvitePharmacyStaffRequest body =
        request == null ? new InvitePharmacyStaffRequest(null, null, null, null) : request;
    return ApiResponse.ok(
        staffService.invite(principal, body.name(), body.email(), body.phone(), body.role()));
  }

  @DeleteMapping("/{id}")
  @Operation(summary = "Deactivate a staff assignment")
  public ApiResponse<Map<String, Object>> deactivate(
      @AuthenticationPrincipal MedmatePrincipal principal, @PathVariable("id") UUID id) {
    return ApiResponse.ok(staffService.deactivate(principal, id));
  }

  @PostMapping("/{id}/reset-password")
  @Operation(summary = "Issue a one-time password reset token for staff")
  public ApiResponse<Map<String, Object>> resetPassword(
      @AuthenticationPrincipal MedmatePrincipal principal, @PathVariable("id") UUID id) {
    return ApiResponse.ok(staffService.issuePasswordReset(principal, id));
  }

  @PutMapping("/{id}/pos-pin")
  @Operation(summary = "Set or replace a staff POS PIN")
  public ApiResponse<Map<String, Object>> setPosPin(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @PathVariable("id") UUID id,
      @RequestBody(required = false) SetPharmacyPosPinRequest request) {
    SetPharmacyPosPinRequest body = request == null ? new SetPharmacyPosPinRequest(null) : request;
    return ApiResponse.ok(staffService.setPosPin(principal, id, body.pin()));
  }
}
