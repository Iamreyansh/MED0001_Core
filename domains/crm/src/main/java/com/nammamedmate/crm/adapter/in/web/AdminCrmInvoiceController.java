package com.nammamedmate.crm.adapter.in.web;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.nammamedmate.crm.application.SaasBillingService;
import com.nammamedmate.kernel.api.ApiResponse;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.RequiresPermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/crm/invoices")
@Tag(name = "Admin CRM SaaS invoices")
public class AdminCrmInvoiceController {

  private final SaasBillingService billing;

  public AdminCrmInvoiceController(SaasBillingService billing) {
    this.billing = billing;
  }

  @GetMapping
  @RequiresPermission("crm:read")
  @Operation(summary = "Admin: list SaaS invoices with billing KPI chips")
  public ApiResponse<Map<String, Object>> list(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String plan,
      @RequestParam(required = false) UUID accountId,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer limit) {
    SaasBillingService.PagedResult result =
        billing.listAdmin(principal, status, plan, accountId, from, to, page, limit);
    return ApiResponse.ok(result.data(), result.meta());
  }

  @GetMapping("/{id}")
  @RequiresPermission("crm:read")
  @Operation(summary = "Admin: SaaS invoice detail")
  public ApiResponse<Map<String, Object>> get(
      @AuthenticationPrincipal MedmatePrincipal principal, @PathVariable("id") UUID id) {
    return ApiResponse.ok(billing.getAdmin(principal, id));
  }

  @PostMapping("/{id}/send-reminder")
  @RequiresPermission("crm:read")
  @Operation(summary = "Admin: send payment reminder (email + WhatsApp via outbox)")
  public ApiResponse<Map<String, Object>> sendReminder(
      @AuthenticationPrincipal MedmatePrincipal principal, @PathVariable("id") UUID id) {
    return ApiResponse.ok(billing.sendReminder(principal, id));
  }

  @PostMapping("/{id}/mark-paid")
  @RequiresPermission("crm:update")
  @Operation(summary = "Admin finance: manually mark invoice paid")
  public ApiResponse<Map<String, Object>> markPaid(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @PathVariable("id") UUID id,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @RequestBody MarkPaidRequest body) {
    return ApiResponse.ok(
        billing.markPaid(
            principal,
            id,
            body == null ? null : body.paymentDate(),
            body == null ? null : body.paymentMode(),
            body == null ? null : body.referenceNumber(),
            idempotencyKey));
  }

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record MarkPaidRequest(
      LocalDate paymentDate, String paymentMode, String referenceNumber) {}
}
