package com.nammamedmate.pharmacy.adapter.in.web;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.nammamedmate.kernel.api.ApiResponse;
import com.nammamedmate.pharmacy.application.PharmacyStorefrontService;
import com.nammamedmate.security.MedmatePrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/pharmacy/storefront")
@Tag(name = "Pharmacy storefront")
public class PharmacyStorefrontController {

  private final PharmacyStorefrontService service;

  public PharmacyStorefrontController(PharmacyStorefrontService service) {
    this.service = service;
  }

  @PatchMapping
  @Operation(summary = "Pharmacy owner: toggle own online/offline status")
  public ApiResponse<Map<String, Object>> toggleStorefront(
      @AuthenticationPrincipal MedmatePrincipal principal, @RequestBody StorefrontRequest body) {
    StorefrontRequest req = body == null ? new StorefrontRequest(null) : body;
    return ApiResponse.ok(service.ownerToggleStorefront(principal, req.isOnline()));
  }

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record StorefrontRequest(Boolean isOnline) {}
}
