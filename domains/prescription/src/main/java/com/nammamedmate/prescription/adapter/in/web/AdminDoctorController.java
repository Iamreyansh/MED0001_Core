package com.nammamedmate.prescription.adapter.in.web;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.nammamedmate.kernel.api.ApiResponse;
import com.nammamedmate.prescription.application.DoctorRegistryService;
import com.nammamedmate.prescription.application.DoctorRegistryService.ListResult;
import com.nammamedmate.prescription.application.DoctorRegistryService.UnverifiedResult;
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
@RequestMapping("/api/v1/admin/doctors")
@Tag(name = "Admin doctor registry")
public class AdminDoctorController {

  private final DoctorRegistryService service;

  public AdminDoctorController(DoctorRegistryService service) {
    this.service = service;
  }

  @GetMapping
  @Operation(summary = "List prescribing doctor directory")
  public ApiResponse<java.util.List<Map<String, Object>>> list(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @RequestParam(name = "search", required = false) String search,
      @RequestParam(name = "specialty", required = false) String specialty,
      @RequestParam(name = "status", required = false) String status,
      @RequestParam(name = "page", required = false) Integer page,
      @RequestParam(name = "limit", required = false) Integer limit,
      @RequestParam(name = "sort", required = false) String sort,
      @RequestParam(name = "order", required = false) String order) {
    ListResult result =
        service.list(principal, search, specialty, status, page, limit, sort, order);
    return ApiResponse.ok(result.data(), result.meta());
  }

  @GetMapping("/unverified")
  @Operation(summary = "List unverified doctors (highest Rx volume first)")
  public ApiResponse<Map<String, Object>> unverified(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @RequestParam(name = "page", required = false) Integer page,
      @RequestParam(name = "limit", required = false) Integer limit,
      @RequestParam(name = "sort", required = false) String sort) {
    UnverifiedResult result = service.listUnverified(principal, page, limit, sort);
    return ApiResponse.ok(result.data(), result.meta());
  }

  @GetMapping("/{id}")
  @Operation(summary = "Get doctor detail")
  public ApiResponse<Map<String, Object>> get(
      @AuthenticationPrincipal MedmatePrincipal principal, @PathVariable UUID id) {
    return ApiResponse.ok(service.get(principal, id));
  }

  @PostMapping("/{id}/verify")
  @Operation(summary = "Manually verify doctor registration (v1)")
  public ApiResponse<Map<String, Object>> verify(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @PathVariable UUID id,
      @RequestBody(required = false) VerifyRequest body) {
    Boolean verified = body == null ? null : body.verified();
    String method = body == null ? null : body.verificationMethod();
    String notes = body == null ? null : body.notes();
    return ApiResponse.ok(service.verify(principal, id, verified, method, notes));
  }

  @PostMapping("/{id}/blacklist")
  @Operation(summary = "Blacklist a doctor (terminal in v1)")
  public ApiResponse<Map<String, Object>> blacklist(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @PathVariable UUID id,
      @RequestBody(required = false) BlacklistRequest body) {
    String reason = body == null ? null : body.reason();
    return ApiResponse.ok(service.blacklist(principal, id, reason));
  }

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record VerifyRequest(Boolean verified, String verificationMethod, String notes) {}

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record BlacklistRequest(String reason) {}
}
