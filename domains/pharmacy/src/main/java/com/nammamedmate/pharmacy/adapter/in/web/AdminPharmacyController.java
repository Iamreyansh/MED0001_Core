package com.nammamedmate.pharmacy.adapter.in.web;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.nammamedmate.kernel.api.ApiResponse;
import com.nammamedmate.pharmacy.application.AdminPharmacyStatusService;
import com.nammamedmate.pharmacy.application.AdminPharmacyStatusService.AdminListResult;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.RequiresPermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.util.List;
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
@RequestMapping("/api/v1/admin/pharmacies")
@Tag(name = "Admin pharmacy status")
public class AdminPharmacyController {

  private final AdminPharmacyStatusService service;

  public AdminPharmacyController(AdminPharmacyStatusService service) {
    this.service = service;
  }

  @GetMapping
  @RequiresPermission("pharmacies:read")
  @Operation(summary = "Admin: list pharmacies (KYC queue / directory)")
  public ApiResponse<Map<String, Object>> list(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @RequestParam(required = false) String status,
      @RequestParam(name = "zone_id", required = false) UUID zoneId,
      @RequestParam(required = false) String plan,
      @RequestParam(name = "is_online", required = false) Boolean isOnline,
      @RequestParam(required = false) String search,
      @RequestParam(required = false) String sort,
      @RequestParam(required = false) String order,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer limit) {
    AdminListResult result =
        service.list(principal, status, zoneId, plan, isOnline, search, sort, order, page, limit);
    return ApiResponse.ok(result.data(), result.meta());
  }

  @GetMapping("/{id}")
  @RequiresPermission("pharmacies:read")
  @Operation(summary = "Admin: pharmacy detail")
  public ApiResponse<Map<String, Object>> detail(
      @AuthenticationPrincipal MedmatePrincipal principal, @PathVariable("id") UUID id) {
    return ApiResponse.ok(service.detail(principal, id));
  }

  @PostMapping("/{id}/approve")
  @RequiresPermission("pharmacies:update")
  @Operation(summary = "Admin: approve KYC and activate pharmacy")
  public ApiResponse<Map<String, Object>> approve(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @PathVariable("id") UUID id,
      @RequestBody(required = false) ApproveRequest body,
      HttpServletRequest request) {
    ApproveRequest req = body == null ? new ApproveRequest(null, null, null) : body;
    return ApiResponse.ok(
        service.approve(
            principal, id, req.commissionPct(), req.zoneId(), req.notes(), clientIp(request)));
  }

  @PostMapping("/{id}/reject")
  @RequiresPermission("pharmacies:update")
  @Operation(summary = "Admin: reject KYC application")
  public ApiResponse<Map<String, Object>> reject(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @PathVariable("id") UUID id,
      @RequestBody(required = false) RejectRequest body,
      HttpServletRequest request) {
    RejectRequest req = body == null ? new RejectRequest(null, null, null) : body;
    return ApiResponse.ok(
        service.reject(
            principal,
            id,
            req.rejectionReason(),
            req.rejectionDetails(),
            req.canReapply(),
            clientIp(request)));
  }

  @PostMapping("/{id}/suspend")
  @RequiresPermission("pharmacies:suspend")
  @Operation(summary = "Admin: suspend active pharmacy")
  public ApiResponse<Map<String, Object>> suspend(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @PathVariable("id") UUID id,
      @RequestBody(required = false) SuspendRequest body,
      HttpServletRequest request) {
    SuspendRequest req = body == null ? new SuspendRequest(null, null, null) : body;
    return ApiResponse.ok(
        service.suspend(
            principal, id, req.reason(), req.suspendType(), req.notes(), clientIp(request)));
  }

  @PostMapping("/{id}/reactivate")
  @RequiresPermission("pharmacies:update")
  @Operation(summary = "Admin: reactivate suspended pharmacy")
  public ApiResponse<Map<String, Object>> reactivate(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @PathVariable("id") UUID id,
      @RequestBody(required = false) ReactivateRequest body,
      HttpServletRequest request) {
    ReactivateRequest req = body == null ? new ReactivateRequest(null) : body;
    return ApiResponse.ok(service.reactivate(principal, id, req.notes(), clientIp(request)));
  }

  @PostMapping("/{id}/request-documents")
  @RequiresPermission("pharmacies:update")
  @Operation(summary = "Admin: request additional KYC documents")
  public ApiResponse<Map<String, Object>> requestDocuments(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @PathVariable("id") UUID id,
      @RequestBody(required = false) RequestDocumentsRequest body,
      HttpServletRequest request) {
    RequestDocumentsRequest req = body == null ? new RequestDocumentsRequest(null, null) : body;
    return ApiResponse.ok(
        service.requestDocuments(
            principal, id, req.documentTypes(), req.message(), clientIp(request)));
  }

  private static String clientIp(HttpServletRequest request) {
    String remote = request.getRemoteAddr();
    return remote == null || remote.isBlank() ? null : remote.trim();
  }

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record ApproveRequest(BigDecimal commissionPct, UUID zoneId, String notes) {}

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record RejectRequest(
      String rejectionReason, String rejectionDetails, Boolean canReapply) {}

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record SuspendRequest(String reason, String suspendType, String notes) {}

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record ReactivateRequest(String notes) {}

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record RequestDocumentsRequest(List<String> documentTypes, String message) {
    public RequestDocumentsRequest {
      documentTypes = documentTypes == null ? null : List.copyOf(documentTypes);
    }
  }
}
