package com.nammamedmate.catalogue.adapter.in.web;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.nammamedmate.catalogue.application.PriceCeilingService;
import com.nammamedmate.catalogue.application.PriceCeilingService.PageResult;
import com.nammamedmate.kernel.api.ApiResponse;
import com.nammamedmate.security.MedmatePrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/catalogue")
@Tag(name = "Admin catalogue price ceilings")
public class AdminPriceCeilingController {

  private final PriceCeilingService service;

  public AdminPriceCeilingController(PriceCeilingService service) {
    this.service = service;
  }

  @GetMapping("/price-ceilings")
  @Operation(summary = "List medicines with active price ceilings")
  public ApiResponse<Map<String, Object>> listCeilings(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @RequestParam(value = "category_id", required = false) UUID categoryId,
      @RequestParam(value = "has_violations", required = false) Boolean hasViolations,
      @RequestParam(value = "page", required = false) Integer page,
      @RequestParam(value = "limit", required = false) Integer limit) {
    PageResult result = service.listCeilings(principal, categoryId, hasViolations, page, limit);
    return ApiResponse.ok(result.data(), result.meta());
  }

  @PostMapping("/{id}/price-ceiling")
  @Operation(summary = "Set or overwrite price ceiling for a medicine")
  public ApiResponse<Map<String, Object>> setCeiling(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @PathVariable("id") UUID id,
      @RequestBody SetCeilingRequest body) {
    SetCeilingRequest req = body == null ? new SetCeilingRequest(null, null, null) : body;
    return ApiResponse.ok(
        service.setCeiling(principal, id, req.ceilingPrice(), req.effectiveFrom(), req.reason()));
  }

  @DeleteMapping("/{id}/price-ceiling")
  @Operation(summary = "Remove price ceiling for a medicine")
  public ApiResponse<Map<String, Object>> removeCeiling(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @PathVariable("id") UUID id,
      @RequestBody RemoveCeilingRequest body) {
    RemoveCeilingRequest req = body == null ? new RemoveCeilingRequest(null) : body;
    return ApiResponse.ok(service.removeCeiling(principal, id, req.reason()));
  }

  @GetMapping("/price-violations")
  @Operation(summary = "List open price ceiling violations")
  public ApiResponse<Map<String, Object>> listViolations(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @RequestParam(value = "medicine_id", required = false) UUID medicineId,
      @RequestParam(value = "zone_id", required = false) UUID zoneId,
      @RequestParam(value = "page", required = false) Integer page,
      @RequestParam(value = "limit", required = false) Integer limit) {
    PageResult result = service.listViolations(principal, medicineId, zoneId, page, limit);
    return ApiResponse.ok(result.data(), result.meta());
  }

  @PostMapping("/price-violations/notify")
  @Operation(summary = "Notify pharmacies with open price ceiling violations")
  public ApiResponse<Map<String, Object>> notifyViolations(
      @AuthenticationPrincipal MedmatePrincipal principal, @RequestBody NotifyRequest body) {
    NotifyRequest req = body == null ? new NotifyRequest(null, null) : body;
    return ApiResponse.ok(service.notifyViolations(principal, req.medicineId(), req.message()));
  }

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record SetCeilingRequest(Object ceilingPrice, String effectiveFrom, String reason) {}

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record RemoveCeilingRequest(String reason) {}

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record NotifyRequest(UUID medicineId, String message) {}
}
