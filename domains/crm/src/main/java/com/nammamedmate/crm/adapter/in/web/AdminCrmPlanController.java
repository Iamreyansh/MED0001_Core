package com.nammamedmate.crm.adapter.in.web;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.nammamedmate.crm.application.SaasPlanService;
import com.nammamedmate.crm.application.SubscriptionService;
import com.nammamedmate.kernel.api.ApiResponse;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.RequiresPermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/crm")
@Tag(name = "Admin CRM SaaS plans")
public class AdminCrmPlanController {

  private final SaasPlanService plans;
  private final SubscriptionService subscriptions;

  public AdminCrmPlanController(SaasPlanService plans, SubscriptionService subscriptions) {
    this.plans = plans;
    this.subscriptions = subscriptions;
  }

  @GetMapping("/plans")
  @RequiresPermission("crm:read")
  @Operation(summary = "Admin: list SaaS plans with subscriber counts and MRR")
  public ApiResponse<Map<String, Object>> listPlans(
      @AuthenticationPrincipal MedmatePrincipal principal) {
    return ApiResponse.ok(plans.listPlansAdmin(principal));
  }

  @GetMapping("/plans/{id}")
  @RequiresPermission("crm:read")
  @Operation(summary = "Admin: SaaS plan detail")
  public ApiResponse<Map<String, Object>> getPlan(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @PathVariable("id") UUID id,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer limit) {
    return ApiResponse.ok(plans.getPlanAdmin(principal, id, page, limit));
  }

  @PatchMapping("/plans/{id}")
  @RequiresPermission("crm:update")
  @Operation(summary = "Admin: update SaaS plan pricing/limits (admin_super)")
  public ApiResponse<Map<String, Object>> updatePlan(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @PathVariable("id") UUID id,
      @RequestBody UpdatePlanRequest body) {
    return ApiResponse.ok(
        plans.updatePlan(
            principal,
            id,
            body == null ? null : body.priceMonthlyRs(),
            body == null ? null : body.seatLimit(),
            body == null ? null : body.invoiceCapMonthly()));
  }

  @GetMapping("/addons")
  @RequiresPermission("crm:read")
  @Operation(summary = "Admin: list SaaS add-ons with attach rate")
  public ApiResponse<Map<String, Object>> listAddons(
      @AuthenticationPrincipal MedmatePrincipal principal) {
    return ApiResponse.ok(plans.listAddons(principal));
  }

  @PostMapping("/accounts/{accountId}/addons/{addonId}")
  @RequiresPermission("crm:write")
  @Operation(summary = "Admin: attach add-on to CRM account")
  public ApiResponse<Map<String, Object>> attachAddon(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @PathVariable UUID accountId,
      @PathVariable UUID addonId) {
    return ApiResponse.ok(plans.attachAddon(principal, accountId, addonId));
  }

  @DeleteMapping("/accounts/{accountId}/addons/{addonId}")
  @RequiresPermission("crm:write")
  @Operation(summary = "Admin: detach add-on with prorated credit")
  public ApiResponse<Map<String, Object>> detachAddon(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @PathVariable UUID accountId,
      @PathVariable UUID addonId) {
    return ApiResponse.ok(plans.detachAddon(principal, accountId, addonId));
  }

  @GetMapping("/module-matrix")
  @RequiresPermission("crm:read")
  @Operation(summary = "Admin: ERP module matrix by plan")
  public ApiResponse<Map<String, Object>> moduleMatrix(
      @AuthenticationPrincipal MedmatePrincipal principal) {
    return ApiResponse.ok(plans.moduleMatrix(principal));
  }

  @PostMapping("/accounts/{accountId}/subscription/override")
  @RequiresPermission("crm:update")
  @Operation(summary = "Admin super: override subscription plan (max 90 days)")
  public ApiResponse<Map<String, Object>> overrideSubscription(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @PathVariable UUID accountId,
      @RequestBody OverrideRequest body) {
    return ApiResponse.ok(
        subscriptions.overrideSubscription(
            principal,
            accountId,
            body == null ? null : body.planId(),
            body == null ? null : body.overrideReason(),
            body == null ? null : body.overrideExpiresAt()));
  }

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record UpdatePlanRequest(
      BigDecimal priceMonthlyRs, Integer seatLimit, Integer invoiceCapMonthly) {}

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record OverrideRequest(UUID planId, String overrideReason, Instant overrideExpiresAt) {}
}
