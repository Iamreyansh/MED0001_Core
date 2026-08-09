package com.nammamedmate.crm.adapter.in.web;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.nammamedmate.crm.application.SaasPlanService;
import com.nammamedmate.crm.application.SubscriptionService;
import com.nammamedmate.kernel.api.ApiResponse;
import com.nammamedmate.security.MedmatePrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/pharmacy/subscription")
@Tag(name = "Pharmacy subscription")
public class PharmacySubscriptionController {

  private final SaasPlanService plans;
  private final SubscriptionService subscriptions;

  public PharmacySubscriptionController(SaasPlanService plans, SubscriptionService subscriptions) {
    this.plans = plans;
    this.subscriptions = subscriptions;
  }

  @GetMapping("/plans")
  @Operation(summary = "Pharmacy owner: view SaaS plan catalogue")
  public ApiResponse<Map<String, Object>> listPlans(
      @AuthenticationPrincipal MedmatePrincipal principal) {
    return ApiResponse.ok(plans.listPlansForPharmacy(principal));
  }

  @PostMapping("/subscribe")
  @Operation(summary = "Subscribe to a SaaS plan")
  public ResponseEntity<ApiResponse<Map<String, Object>>> subscribe(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @RequestBody SubscribeRequest body) {
    Map<String, Object> data =
        subscriptions.subscribe(
            principal,
            body == null ? null : body.planId(),
            body == null ? null : body.billingCycle(),
            body == null ? null : body.couponCode(),
            idempotencyKey);
    return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(data));
  }

  @PostMapping("/upgrade")
  @Operation(summary = "Upgrade plan mid-cycle with proration")
  public ApiResponse<Map<String, Object>> upgrade(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @RequestBody UpgradeRequest body) {
    return ApiResponse.ok(
        subscriptions.upgrade(principal, body == null ? null : body.newPlanId(), idempotencyKey));
  }

  @PostMapping("/downgrade")
  @Operation(summary = "Schedule plan downgrade at renewal")
  public ApiResponse<Map<String, Object>> downgrade(
      @AuthenticationPrincipal MedmatePrincipal principal, @RequestBody DowngradeRequest body) {
    return ApiResponse.ok(
        subscriptions.downgrade(principal, body == null ? null : body.newPlanId()));
  }

  @PostMapping("/cancel")
  @Operation(summary = "Cancel subscription at end of period")
  public ApiResponse<Map<String, Object>> cancel(
      @AuthenticationPrincipal MedmatePrincipal principal) {
    return ApiResponse.ok(subscriptions.cancel(principal));
  }

  @GetMapping
  @Operation(summary = "Get current subscription")
  public ApiResponse<Map<String, Object>> current(
      @AuthenticationPrincipal MedmatePrincipal principal) {
    return ApiResponse.ok(subscriptions.getCurrent(principal));
  }

  @PatchMapping("/auto-renew")
  @Operation(summary = "Toggle auto-renew")
  public ApiResponse<Map<String, Object>> autoRenew(
      @AuthenticationPrincipal MedmatePrincipal principal, @RequestBody AutoRenewRequest body) {
    boolean enabled = body != null && Boolean.TRUE.equals(body.enabled());
    return ApiResponse.ok(subscriptions.setAutoRenew(principal, enabled));
  }

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record SubscribeRequest(UUID planId, String billingCycle, String couponCode) {}

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record UpgradeRequest(UUID newPlanId) {}

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record DowngradeRequest(UUID newPlanId) {}

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record AutoRenewRequest(Boolean enabled) {}
}
