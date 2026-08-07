package com.nammamedmate.catalogue.adapter.in.web;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.nammamedmate.catalogue.application.MappingService;
import com.nammamedmate.catalogue.application.MappingService.PageResult;
import com.nammamedmate.kernel.api.ApiResponse;
import com.nammamedmate.security.MedmatePrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
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
@RequestMapping("/api/v1/pharmacy/catalogue-mapping")
@Tag(name = "Pharmacy catalogue mapping")
public class PharmacyCatalogueMappingController {

  private final MappingService service;

  public PharmacyCatalogueMappingController(MappingService service) {
    this.service = service;
  }

  @GetMapping
  @Operation(summary = "List own pharmacy catalogue mappings")
  public ApiResponse<Map<String, Object>> list(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @RequestParam(value = "is_visible", required = false) Boolean isVisible,
      @RequestParam(value = "in_stock", required = false) Boolean inStock,
      @RequestParam(value = "category_id", required = false) UUID categoryId,
      @RequestParam(value = "search", required = false) String search,
      @RequestParam(value = "sort", required = false) String sort,
      @RequestParam(value = "order", required = false) String order,
      @RequestParam(value = "page", required = false) Integer page,
      @RequestParam(value = "limit", required = false) Integer limit) {
    PageResult result =
        service.list(principal, isVisible, inStock, categoryId, search, sort, order, page, limit);
    return ApiResponse.ok(result.data(), result.meta());
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  @Operation(summary = "Create catalogue mapping")
  public ApiResponse<Map<String, Object>> create(
      @AuthenticationPrincipal MedmatePrincipal principal, @RequestBody CreateRequest body) {
    CreateRequest req = body == null ? new CreateRequest(null, null, null) : body;
    return ApiResponse.ok(
        service.create(
            principal, req.masterMedicineId(), req.pharmacyPrice(), req.stockQuantity()));
  }

  @PatchMapping("/{mappingId}")
  @Operation(summary = "Update mapping price, stock, or visibility")
  public ApiResponse<Map<String, Object>> update(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @PathVariable("mappingId") UUID mappingId,
      @RequestBody UpdateRequest body) {
    UpdateRequest req = body == null ? new UpdateRequest(null, null, null) : body;
    return ApiResponse.ok(
        service.update(
            principal, mappingId, req.pharmacyPrice(), req.stockQuantity(), req.isVisible()));
  }

  @DeleteMapping("/{mappingId}")
  @Operation(summary = "Delete catalogue mapping")
  public ApiResponse<Map<String, Object>> delete(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @PathVariable("mappingId") UUID mappingId) {
    return ApiResponse.ok(service.delete(principal, mappingId));
  }

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record CreateRequest(UUID masterMedicineId, Object pharmacyPrice, Integer stockQuantity) {}

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record UpdateRequest(Object pharmacyPrice, Integer stockQuantity, Boolean isVisible) {}
}
