package com.nammamedmate.pharmacy.adapter.in.web;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.nammamedmate.kernel.api.ApiResponse;
import com.nammamedmate.pharmacy.application.AdminPharmacyPerformanceService;
import com.nammamedmate.pharmacy.application.AdminPharmacyPerformanceService.PagedResult;
import com.nammamedmate.pharmacy.application.AdminPharmacyStatusService;
import com.nammamedmate.pharmacy.application.AdminPharmacyStatusService.AdminListResult;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.RequiresPermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
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
  private final AdminPharmacyPerformanceService performanceService;

  public AdminPharmacyController(
      AdminPharmacyStatusService service, AdminPharmacyPerformanceService performanceService) {
    this.service = service;
    this.performanceService = performanceService;
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

  @GetMapping("/summary")
  @RequiresPermission("pharmacies:read")
  @Operation(summary = "Admin: pharmacy directory summary chips")
  public ApiResponse<Map<String, Object>> summary(
      @AuthenticationPrincipal MedmatePrincipal principal) {
    return ApiResponse.ok(service.summary(principal));
  }

  @GetMapping(value = "/export", produces = "text/csv")
  @RequiresPermission("pharmacies:read")
  @Operation(summary = "Admin: export pharmacy directory CSV")
  public void export(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @RequestParam(required = false) String status,
      @RequestParam(name = "zone_id", required = false) UUID zoneId,
      @RequestParam(required = false) String plan,
      @RequestParam(required = false) String search,
      HttpServletResponse response)
      throws Exception {
    String filename = "pharmacies-export-" + LocalDate.now(ZoneOffset.UTC) + ".csv";
    response.setStatus(HttpServletResponse.SC_OK);
    response.setContentType("text/csv; charset=UTF-8");
    response.setHeader(
        HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"");
    service.export(principal, status, zoneId, plan, search, response.getOutputStream());
  }

  @GetMapping("/{id}")
  @RequiresPermission("pharmacies:read")
  @Operation(summary = "Admin: pharmacy detail")
  public ApiResponse<Map<String, Object>> detail(
      @AuthenticationPrincipal MedmatePrincipal principal, @PathVariable("id") UUID id) {
    return ApiResponse.ok(service.detail(principal, id));
  }

  @GetMapping("/{id}/performance")
  @RequiresPermission("pharmacies:read")
  @Operation(summary = "Admin: pharmacy performance metrics")
  public ApiResponse<Map<String, Object>> performance(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @PathVariable("id") UUID id,
      @RequestParam(required = false) String period) {
    return ApiResponse.ok(performanceService.performance(principal, id, period));
  }

  @GetMapping("/{id}/ratings")
  @RequiresPermission("pharmacies:read")
  @Operation(summary = "Admin: pharmacy customer ratings")
  public ApiResponse<Map<String, Object>> ratings(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @PathVariable("id") UUID id,
      @RequestParam(required = false) Integer rating,
      @RequestParam(required = false) String sort,
      @RequestParam(required = false) String order,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer limit) {
    PagedResult result =
        performanceService.ratings(principal, id, rating, sort, order, page, limit);
    return ApiResponse.ok(result.data(), result.meta());
  }

  @GetMapping("/{id}/orders")
  @RequiresPermission("pharmacies:read")
  @Operation(summary = "Admin: pharmacy recent orders")
  public ApiResponse<Map<String, Object>> orders(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @PathVariable("id") UUID id,
      @RequestParam(required = false) String status,
      @RequestParam(name = "from_date", required = false) LocalDate fromDate,
      @RequestParam(name = "to_date", required = false) LocalDate toDate,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer limit) {
    PagedResult result =
        performanceService.orders(principal, id, status, fromDate, toDate, page, limit);
    return ApiResponse.ok(result.data(), result.meta());
  }

  @PostMapping("/{id}/performance/alert")
  @RequiresPermission("pharmacies:update")
  @Operation(summary = "Admin: send performance alert to pharmacy")
  public ApiResponse<Map<String, Object>> performanceAlert(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @PathVariable("id") UUID id,
      @RequestBody PerformanceAlertRequest body) {
    PerformanceAlertRequest req =
        body == null ? new PerformanceAlertRequest(null, null, null) : body;
    return ApiResponse.ok(
        performanceService.sendAlert(
            principal, id, req.alertType(), req.thresholdValue(), req.message()));
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

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record PerformanceAlertRequest(
      String alertType, BigDecimal thresholdValue, String message) {}
}
