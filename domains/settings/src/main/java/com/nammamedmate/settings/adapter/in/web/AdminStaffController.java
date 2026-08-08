package com.nammamedmate.settings.adapter.in.web;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.nammamedmate.kernel.api.ApiResponse;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.settings.application.AdminStaffService;
import com.nammamedmate.settings.application.AdminStaffService.ListResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/staff")
@Tag(name = "Admin staff")
public class AdminStaffController {

  private final AdminStaffService service;

  public AdminStaffController(AdminStaffService service) {
    this.service = service;
  }

  @GetMapping
  @Operation(summary = "List admin staff")
  public ApiResponse<java.util.List<Map<String, Object>>> list(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer limit,
      @RequestParam(required = false) String role,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String search) {
    ListResult result = service.list(principal, page, limit, role, status, search);
    return ApiResponse.ok(result.data(), result.meta());
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  @Operation(summary = "Invite admin staff")
  public ApiResponse<Map<String, Object>> invite(
      @AuthenticationPrincipal MedmatePrincipal principal, @RequestBody InviteRequest body) {
    InviteRequest req = body == null ? new InviteRequest(null, null, null, null) : body;
    return ApiResponse.ok(
        service.invite(principal, req.name(), req.email(), req.role(), req.sendInviteEmail()));
  }

  @GetMapping("/{id}")
  @Operation(summary = "Get admin staff detail")
  public ApiResponse<Map<String, Object>> get(
      @AuthenticationPrincipal MedmatePrincipal principal, @PathVariable("id") UUID id) {
    return ApiResponse.ok(service.get(principal, id));
  }

  @PatchMapping("/{id}")
  @Operation(summary = "Update admin staff")
  public ApiResponse<Map<String, Object>> update(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @PathVariable("id") UUID id,
      @RequestBody(required = false) UpdateRequest body) {
    UpdateRequest req = body == null ? new UpdateRequest(null, null, null) : body;
    return ApiResponse.ok(service.update(principal, id, req.name(), req.role(), req.status()));
  }

  @DeleteMapping("/{id}")
  @Operation(summary = "Remove admin staff")
  public ApiResponse<Map<String, Object>> delete(
      @AuthenticationPrincipal MedmatePrincipal principal, @PathVariable("id") UUID id) {
    return ApiResponse.ok(service.delete(principal, id));
  }

  @PostMapping("/{id}/reset-password")
  @Operation(summary = "Send password reset email")
  public ApiResponse<Map<String, Object>> resetPassword(
      @AuthenticationPrincipal MedmatePrincipal principal, @PathVariable("id") UUID id) {
    return ApiResponse.ok(service.resetPassword(principal, id));
  }

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record InviteRequest(String name, String email, String role, Boolean sendInviteEmail) {}

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record UpdateRequest(String name, String role, String status) {}
}
