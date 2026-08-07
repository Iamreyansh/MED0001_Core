package com.nammamedmate.catalogue.adapter.in.web;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.nammamedmate.catalogue.application.MappingService;
import com.nammamedmate.catalogue.application.MappingService.PageResult;
import com.nammamedmate.kernel.api.ApiResponse;
import com.nammamedmate.security.MedmatePrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/catalogue")
@Tag(name = "Admin catalogue mapping")
public class AdminCatalogueMappingController {

  private final MappingService service;

  public AdminCatalogueMappingController(MappingService service) {
    this.service = service;
  }

  @GetMapping("/{masterId}/pharmacy-mappings")
  @Operation(summary = "List pharmacies stocking a master medicine")
  public ApiResponse<Map<String, Object>> pharmacyMappings(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @PathVariable("masterId") UUID masterId,
      @RequestParam(value = "zone_id", required = false) UUID zoneId,
      @RequestParam(value = "is_visible", required = false) Boolean isVisible,
      @RequestParam(value = "above_ceiling", required = false) Boolean aboveCeiling,
      @RequestParam(value = "page", required = false) Integer page,
      @RequestParam(value = "limit", required = false) Integer limit) {
    PageResult result =
        service.adminList(principal, masterId, zoneId, isVisible, aboveCeiling, page, limit);
    return ApiResponse.ok(result.data(), result.meta());
  }

  @PostMapping("/bulk-map")
  @ResponseStatus(HttpStatus.ACCEPTED)
  @Operation(summary = "Bulk-map a medicine to many pharmacies")
  public ApiResponse<Map<String, Object>> bulkMap(
      @AuthenticationPrincipal MedmatePrincipal principal, @RequestBody BulkMapRequest body) {
    BulkMapRequest req = body == null ? new BulkMapRequest(null, null, null, null, null) : body;
    return ApiResponse.ok(
        service.bulkMap(
            principal,
            req.masterMedicineId(),
            req.pharmacyIds(),
            req.autoPriceFromMrp(),
            req.pharmacyPrice(),
            req.initialStockQuantity()));
  }

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record BulkMapRequest(
      UUID masterMedicineId,
      List<UUID> pharmacyIds,
      Boolean autoPriceFromMrp,
      Object pharmacyPrice,
      Integer initialStockQuantity) {
    public BulkMapRequest {
      pharmacyIds = pharmacyIds == null ? null : List.copyOf(pharmacyIds);
    }
  }
}
