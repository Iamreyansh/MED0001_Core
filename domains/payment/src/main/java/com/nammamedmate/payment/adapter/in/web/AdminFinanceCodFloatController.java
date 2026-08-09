package com.nammamedmate.payment.adapter.in.web;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.nammamedmate.kernel.api.ApiResponse;
import com.nammamedmate.payment.application.CodFloatFacadeService;
import com.nammamedmate.payment.application.CodFloatFacadeService.PagedResult;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.RequiresPermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/finance/cod-float")
@Tag(name = "Admin COD float finance")
public class AdminFinanceCodFloatController {

  private final CodFloatFacadeService floats;

  public AdminFinanceCodFloatController(CodFloatFacadeService floats) {
    this.floats = floats;
  }

  @GetMapping
  @RequiresPermission("finance:read")
  @Operation(summary = "Admin: platform COD float summary")
  public ApiResponse<Map<String, Object>> summary(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @RequestParam(name = "zone_id", required = false) UUID zoneId,
      @RequestParam(name = "risk_only", required = false) Boolean riskOnly,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer limit) {
    PagedResult result = floats.floatSummary(principal, zoneId, riskOnly, page, limit);
    return ApiResponse.ok(result.data(), result.meta());
  }

  @GetMapping("/reconciliation-report")
  @RequiresPermission("finance:read")
  @Operation(summary = "Admin finance: daily COD reconciliation report")
  public ApiResponse<Map<String, Object>> reconciliationReport(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
          LocalDate date) {
    return ApiResponse.ok(floats.reconciliationReport(principal, date));
  }

  @GetMapping(value = "/reconciliation-report/export", produces = "text/csv")
  @RequiresPermission("finance:read")
  @Operation(summary = "Admin finance: export COD reconciliation report CSV")
  public void export(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
      HttpServletResponse response)
      throws Exception {
    LocalDate resolved = date == null ? LocalDate.now(ZoneOffset.UTC).minusDays(1) : date;
    String filename = "cod-reconciliation-" + resolved + ".csv";
    response.setStatus(HttpServletResponse.SC_OK);
    response.setContentType("text/csv; charset=UTF-8");
    response.setHeader(
        HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"");
    floats.exportReconciliationCsv(principal, date, response.getOutputStream());
  }

  @PostMapping("/auto-reconcile")
  @RequiresPermission("finance:update")
  @Operation(summary = "Admin finance: trigger COD auto-reconcile job")
  public ApiResponse<Map<String, Object>> autoReconcile(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @RequestBody(required = false) AutoReconcileRequest body) {
    LocalDate date = body == null ? null : body.date();
    return ApiResponse.ok(floats.autoReconcile(principal, date));
  }

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record AutoReconcileRequest(LocalDate date) {}
}
