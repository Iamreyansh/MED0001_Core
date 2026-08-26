package com.nammamedmate.crm.adapter.in.web;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.nammamedmate.crm.application.SaasBillingService;
import com.nammamedmate.kernel.api.ApiResponse;
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
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/pharmacy/billing")
@Tag(name = "Pharmacy SaaS billing")
public class PharmacyBillingController {

  private final SaasBillingService billing;

  public PharmacyBillingController(SaasBillingService billing) {
    this.billing = billing;
  }

  @GetMapping("/invoices")
  @Operation(summary = "Pharmacy owner: list own SaaS invoices")
  public ApiResponse<Map<String, Object>> list(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer limit) {
    SaasBillingService.PagedResult result = billing.listPharmacy(principal, page, limit);
    return ApiResponse.ok(result.data(), result.meta());
  }

  @GetMapping("/invoices/{id}")
  @Operation(summary = "Pharmacy owner: invoice detail with signed PDF download URL")
  public ApiResponse<Map<String, Object>> get(
      @AuthenticationPrincipal MedmatePrincipal principal, @PathVariable("id") UUID id) {
    return ApiResponse.ok(billing.getPharmacy(principal, id));
  }

  @PostMapping("/pay")
  @Operation(summary = "Pharmacy owner: initiate Cashfree checkout for an invoice")
  public ApiResponse<Map<String, Object>> pay(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @RequestBody PayRequest body) {
    return ApiResponse.ok(
        billing.pay(
            principal,
            body == null ? null : body.invoiceId(),
            body == null ? null : body.paymentMethod(),
            idempotencyKey));
  }

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record PayRequest(UUID invoiceId, String paymentMethod) {}
}
