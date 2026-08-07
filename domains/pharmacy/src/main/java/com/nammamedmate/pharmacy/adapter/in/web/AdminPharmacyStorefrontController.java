package com.nammamedmate.pharmacy.adapter.in.web;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.nammamedmate.kernel.api.ApiResponse;
import com.nammamedmate.pharmacy.application.CataloguePauseService;
import com.nammamedmate.pharmacy.application.PharmacyStorefrontService;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.RequiresPermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/pharmacies")
@Tag(name = "Admin pharmacy storefront")
public class AdminPharmacyStorefrontController {

  private final PharmacyStorefrontService storefrontService;
  private final CataloguePauseService cataloguePauseService;

  public AdminPharmacyStorefrontController(
      PharmacyStorefrontService storefrontService, CataloguePauseService cataloguePauseService) {
    this.storefrontService = storefrontService;
    this.cataloguePauseService = cataloguePauseService;
  }

  @PatchMapping("/{id}/storefront")
  @RequiresPermission("pharmacies:update")
  @Operation(summary = "Admin: toggle pharmacy online/offline")
  public ApiResponse<Map<String, Object>> toggleStorefront(
      @org.springframework.security.core.annotation.AuthenticationPrincipal
          MedmatePrincipal principal,
      @PathVariable("id") UUID id,
      @RequestBody StorefrontRequest body,
      HttpServletRequest request) {
    StorefrontRequest req = body == null ? new StorefrontRequest(null, null) : body;
    return ApiResponse.ok(
        storefrontService.adminToggleStorefront(
            principal, id, req.isOnline(), req.reason(), clientIp(request)));
  }

  @PatchMapping("/{id}/zone")
  @RequiresPermission("pharmacies:update")
  @Operation(summary = "Admin: reassign pharmacy to a delivery zone")
  public ApiResponse<Map<String, Object>> reassignZone(
      @org.springframework.security.core.annotation.AuthenticationPrincipal
          MedmatePrincipal principal,
      @PathVariable("id") UUID id,
      @RequestBody ZoneRequest body,
      HttpServletRequest request) {
    ZoneRequest req = body == null ? new ZoneRequest(null, null) : body;
    return ApiResponse.ok(
        storefrontService.reassignZone(
            principal, id, req.zoneId(), req.effectiveFrom(), clientIp(request)));
  }

  @PostMapping("/{id}/catalogue/pause")
  @RequiresPermission("pharmacies:update")
  @Operation(summary = "Admin: temporarily pause pharmacy catalogue")
  public ApiResponse<Map<String, Object>> pauseCatalogue(
      @org.springframework.security.core.annotation.AuthenticationPrincipal
          MedmatePrincipal principal,
      @PathVariable("id") UUID id,
      @RequestBody CataloguePauseRequest body,
      HttpServletRequest request) {
    CataloguePauseRequest req = body == null ? new CataloguePauseRequest(null, null) : body;
    return ApiResponse.ok(
        cataloguePauseService.pauseCatalogue(
            principal, id, req.durationMinutes(), req.reason(), clientIp(request)));
  }

  private static String clientIp(HttpServletRequest request) {
    String remote = request.getRemoteAddr();
    return remote == null || remote.isBlank() ? null : remote.trim();
  }

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record StorefrontRequest(Boolean isOnline, String reason) {}

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record ZoneRequest(UUID zoneId, Instant effectiveFrom) {}

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record CataloguePauseRequest(Integer durationMinutes, String reason) {}
}
