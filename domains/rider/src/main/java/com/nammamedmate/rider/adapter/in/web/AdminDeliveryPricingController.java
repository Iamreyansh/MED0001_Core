package com.nammamedmate.rider.adapter.in.web;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.nammamedmate.kernel.api.ApiResponse;
import com.nammamedmate.rider.application.DeliveryPricingService;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.RequiresPermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/zones")
@Tag(name = "Admin delivery pricing")
public class AdminDeliveryPricingController {

  private final DeliveryPricingService service;

  public AdminDeliveryPricingController(DeliveryPricingService service) {
    this.service = service;
  }

  @GetMapping("/pricing")
  @RequiresPermission("riders:read")
  @Operation(summary = "Admin: per-zone delivery fee structure")
  public ApiResponse<Map<String, Object>> listPricing(
      @AuthenticationPrincipal MedmatePrincipal principal) {
    return ApiResponse.ok(service.listPricing(principal));
  }

  @PostMapping("/pricing/simulate")
  @RequiresPermission("riders:read")
  @Operation(summary = "Admin: fee simulator (planning only)")
  public ApiResponse<Map<String, Object>> simulate(
      @AuthenticationPrincipal MedmatePrincipal principal, @RequestBody SimulateRequest body) {
    return ApiResponse.ok(
        service.simulate(
            principal,
            body == null ? null : body.zoneId(),
            body == null ? null : body.distanceKm(),
            body == null ? null : body.orderValue()));
  }

  @PatchMapping("/{id}/pricing")
  @RequiresPermission("riders:assign")
  @Operation(summary = "Admin: patch zone delivery pricing")
  public ApiResponse<Map<String, Object>> patchPricing(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @PathVariable("id") UUID id,
      @RequestBody PatchPricingRequest body) {
    return ApiResponse.ok(
        service.patchPricing(
            principal,
            id,
            body == null ? null : body.baseFee(),
            body == null ? null : body.perKmFee(),
            body == null ? null : body.slaMinutes(),
            body == null ? null : body.minOrderValue(),
            body == null ? null : body.freeDeliveryThreshold()));
  }

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record SimulateRequest(UUID zoneId, BigDecimal distanceKm, BigDecimal orderValue) {}

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record PatchPricingRequest(
      BigDecimal baseFee,
      BigDecimal perKmFee,
      Integer slaMinutes,
      BigDecimal minOrderValue,
      BigDecimal freeDeliveryThreshold) {}
}
