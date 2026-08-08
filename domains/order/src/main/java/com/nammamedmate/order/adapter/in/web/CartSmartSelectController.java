package com.nammamedmate.order.adapter.in.web;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.nammamedmate.kernel.api.ApiResponse;
import com.nammamedmate.order.application.SmartPharmacySelectionService;
import com.nammamedmate.security.MedmatePrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/cart")
@Tag(name = "Cart smart-select")
public class CartSmartSelectController {

  private final SmartPharmacySelectionService service;

  public CartSmartSelectController(SmartPharmacySelectionService service) {
    this.service = service;
  }

  @PostMapping("/smart-select")
  @Operation(summary = "Auto-select best pharmacy for a medicine near the customer")
  public ApiResponse<Map<String, Object>> smartSelect(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @RequestBody(required = false) SmartSelectRequest body) {
    SmartSelectRequest req = body == null ? new SmartSelectRequest(null, null, null) : body;
    return ApiResponse.ok(service.smartSelect(principal, req.medicineId(), req.lat(), req.lng()));
  }

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record SmartSelectRequest(UUID medicineId, Double lat, Double lng) {}
}
