package com.nammamedmate.rider.adapter.in.web;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.nammamedmate.kernel.api.ApiResponse;
import com.nammamedmate.rider.application.AdminDeliveryZoneService;
import com.nammamedmate.rider.application.AdminDeliveryZoneService.CreateZoneCommand;
import com.nammamedmate.rider.application.AdminDeliveryZoneService.ListResult;
import com.nammamedmate.rider.application.AdminDeliveryZoneService.PatchZoneCommand;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.RequiresPermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
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
@RequestMapping("/api/v1/admin/zones")
@Tag(name = "Admin delivery zones")
public class AdminDeliveryZoneController {

  private final AdminDeliveryZoneService service;

  public AdminDeliveryZoneController(AdminDeliveryZoneService service) {
    this.service = service;
  }

  @GetMapping
  @RequiresPermission("riders:read")
  @Operation(summary = "Admin: list delivery zones with KPIs")
  public ApiResponse<Map<String, Object>> list(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @RequestParam(required = false) String city,
      @RequestParam(name = "is_serviceable", required = false) Boolean isServiceable,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer limit) {
    ListResult result = service.list(principal, city, isServiceable, page, limit);
    return ApiResponse.ok(result.data(), result.meta());
  }

  @PostMapping
  @RequiresPermission("riders:assign")
  @ResponseStatus(HttpStatus.CREATED)
  @Operation(summary = "Admin: create delivery zone")
  public ApiResponse<Map<String, Object>> create(
      @AuthenticationPrincipal MedmatePrincipal principal, @RequestBody CreateZoneRequest body) {
    return ApiResponse.ok(
        service.create(
            principal,
            new CreateZoneCommand(
                body == null ? null : body.zoneName(),
                body == null ? null : body.city(),
                body == null ? null : body.state(),
                body == null ? null : body.polygon(),
                body == null ? null : body.baseFee(),
                body == null ? null : body.perKmFee(),
                body == null ? null : body.slaMinutes(),
                body == null ? null : body.minOrderValue(),
                body == null ? null : body.freeDeliveryThreshold(),
                body == null ? null : body.surgeMultiplier(),
                body == null ? null : body.isServiceable())));
  }

  @GetMapping("/rebalancing-suggestions")
  @RequiresPermission("riders:read")
  @Operation(summary = "Admin: AI/stub rider rebalancing suggestions")
  public ApiResponse<Map<String, Object>> rebalancingSuggestions(
      @AuthenticationPrincipal MedmatePrincipal principal) {
    return ApiResponse.ok(service.rebalancingSuggestions(principal));
  }

  @PostMapping("/rebalancing/{suggestion_id}/apply")
  @RequiresPermission("riders:assign")
  @Operation(summary = "Admin: apply rebalancing suggestion")
  public ApiResponse<Map<String, Object>> applyRebalancing(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @PathVariable("suggestion_id") UUID suggestionId) {
    return ApiResponse.ok(service.applyRebalancing(principal, suggestionId));
  }

  @GetMapping("/demand-vs-supply")
  @RequiresPermission("riders:read")
  @Operation(summary = "Admin: hourly demand vs supply chart")
  public ApiResponse<Map<String, Object>> demandVsSupply(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @RequestParam(name = "zone_id", required = false) UUID zoneId,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
    return ApiResponse.ok(service.demandVsSupply(principal, zoneId, from, to));
  }

  @GetMapping("/{id}")
  @RequiresPermission("riders:read")
  @Operation(summary = "Admin: zone detail")
  public ApiResponse<Map<String, Object>> get(
      @AuthenticationPrincipal MedmatePrincipal principal, @PathVariable("id") UUID id) {
    return ApiResponse.ok(service.get(principal, id));
  }

  @PatchMapping("/{id}")
  @RequiresPermission("riders:assign")
  @Operation(summary = "Admin: patch zone config")
  public ApiResponse<Map<String, Object>> patch(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @PathVariable("id") UUID id,
      @RequestBody PatchZoneRequest body) {
    return ApiResponse.ok(
        service.patch(
            principal,
            id,
            new PatchZoneCommand(
                body == null ? null : body.zoneName(),
                body == null ? null : body.slaMinutes(),
                body == null ? null : body.baseFee(),
                body == null ? null : body.perKmFee(),
                body == null ? null : body.minOrderValue(),
                body == null ? null : body.freeDeliveryThreshold(),
                body == null ? null : body.polygon())));
  }

  @PatchMapping("/{id}/surge")
  @RequiresPermission("riders:assign")
  @Operation(summary = "Admin: toggle zone surge")
  public ApiResponse<Map<String, Object>> surge(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @PathVariable("id") UUID id,
      @RequestBody SurgeRequest body) {
    return ApiResponse.ok(
        service.setSurge(
            principal,
            id,
            body == null ? null : body.isSurgeActive(),
            body == null ? null : body.surgeMultiplier()));
  }

  @PatchMapping("/{id}/serviceable")
  @RequiresPermission("riders:assign")
  @Operation(summary = "Admin: toggle zone serviceability")
  public ApiResponse<Map<String, Object>> serviceable(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @PathVariable("id") UUID id,
      @RequestBody ServiceableRequest body) {
    return ApiResponse.ok(
        service.setServiceable(
            principal,
            id,
            body == null ? null : body.isServiceable(),
            body == null ? null : body.reason()));
  }

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record CreateZoneRequest(
      String zoneName,
      String city,
      String state,
      Map<String, Object> polygon,
      BigDecimal baseFee,
      BigDecimal perKmFee,
      Integer slaMinutes,
      BigDecimal minOrderValue,
      BigDecimal freeDeliveryThreshold,
      BigDecimal surgeMultiplier,
      Boolean isServiceable) {}

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record PatchZoneRequest(
      String zoneName,
      Integer slaMinutes,
      BigDecimal baseFee,
      BigDecimal perKmFee,
      BigDecimal minOrderValue,
      BigDecimal freeDeliveryThreshold,
      Map<String, Object> polygon) {}

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record SurgeRequest(Boolean isSurgeActive, BigDecimal surgeMultiplier) {}

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record ServiceableRequest(Boolean isServiceable, String reason) {}
}
