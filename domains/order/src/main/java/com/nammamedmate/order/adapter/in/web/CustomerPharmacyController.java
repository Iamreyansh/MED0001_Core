package com.nammamedmate.order.adapter.in.web;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.nammamedmate.kernel.api.ApiResponse;
import com.nammamedmate.order.application.PharmacyDiscoveryService;
import com.nammamedmate.order.application.PharmacyDiscoveryService.NearbyResult;
import com.nammamedmate.order.application.PharmacyDiscoveryService.ProductsResult;
import com.nammamedmate.security.MedmatePrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/pharmacies")
@Tag(name = "Customer pharmacy discovery")
public class CustomerPharmacyController {

  private final PharmacyDiscoveryService service;

  public CustomerPharmacyController(PharmacyDiscoveryService service) {
    this.service = service;
  }

  @GetMapping("/nearby")
  @Operation(summary = "List open pharmacies near the customer")
  public Map<String, Object> nearby(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @RequestParam("lat") Double lat,
      @RequestParam("lng") Double lng,
      @RequestParam(value = "radius_km", required = false) Double radiusKm,
      @RequestParam(value = "limit", required = false) Integer limit) {
    NearbyResult result = service.nearby(principal, lat, lng, radiusKm, limit);
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("success", true);
    body.put("data", result.data());
    body.put("meta", result.meta());
    return body;
  }

  @GetMapping("/{id}")
  @Operation(summary = "Pharmacy storefront")
  public ApiResponse<Map<String, Object>> storefront(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @PathVariable("id") UUID id,
      @RequestParam(value = "lat", required = false) Double lat,
      @RequestParam(value = "lng", required = false) Double lng) {
    return ApiResponse.ok(service.storefront(principal, id, lat, lng));
  }

  @GetMapping("/{id}/products")
  @Operation(summary = "Visible in-stock products at a pharmacy")
  public Map<String, Object> products(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @PathVariable("id") UUID id,
      @RequestParam(value = "category", required = false) String category,
      @RequestParam(value = "search", required = false) String search,
      @RequestParam(value = "page", required = false) Integer page,
      @RequestParam(value = "limit", required = false) Integer limit) {
    ProductsResult result = service.products(principal, id, category, search, page, limit);
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("success", true);
    body.put("data", result.data());
    body.put("meta", result.meta());
    return body;
  }

  @PostMapping("/availability-check")
  @Operation(summary = "Check medicine availability at a pharmacy")
  public ApiResponse<Map<String, Object>> availabilityCheck(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @RequestBody(required = false) AvailabilityCheckRequest body) {
    AvailabilityCheckRequest req = body == null ? new AvailabilityCheckRequest(null, null) : body;
    return ApiResponse.ok(
        service.availabilityCheck(principal, req.pharmacyId(), req.medicineIds()));
  }

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record AvailabilityCheckRequest(UUID pharmacyId, List<UUID> medicineIds) {
    public AvailabilityCheckRequest {
      medicineIds = medicineIds == null ? null : List.copyOf(medicineIds);
    }
  }
}
