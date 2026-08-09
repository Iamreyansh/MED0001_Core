package com.nammamedmate.inventory.adapter.in.web;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.nammamedmate.inventory.application.InventoryProductService;
import com.nammamedmate.inventory.application.InventoryProductService.ExcelExport;
import com.nammamedmate.inventory.application.InventoryProductService.ListPage;
import com.nammamedmate.inventory.application.RackLocationService;
import com.nammamedmate.kernel.api.ApiResponse;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.security.MedmatePrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/pharmacy/inventory")
@Tag(name = "Pharmacy inventory")
public class PharmacyInventoryController {

  private final InventoryProductService service;
  private final RackLocationService rackLocationService;

  public PharmacyInventoryController(
      InventoryProductService service, RackLocationService rackLocationService) {
    this.service = service;
    this.rackLocationService = rackLocationService;
  }

  @GetMapping
  @Operation(summary = "List pharmacy inventory (paginated) or export EXCEL")
  public Object list(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @RequestParam(value = "tab", required = false) String tab,
      @RequestParam(value = "q", required = false) String q,
      @RequestParam(value = "sort", required = false) String sort,
      @RequestParam(value = "order", required = false) String order,
      @RequestParam(value = "page", required = false) Integer page,
      @RequestParam(value = "limit", required = false) Integer limit,
      @RequestParam(value = "export", required = false) String export,
      @RequestParam(value = "category_id", required = false) UUID categoryId) {
    if (export != null && !export.isBlank()) {
      String kind = export.trim().toUpperCase(Locale.ROOT);
      if (!"EXCEL".equals(kind)) {
        // ponytail: PDF export deferred; EXCEL only for STORY-001
        throw new AppException("VALIDATION_ERROR", "Only export=EXCEL is supported", 400);
      }
      ExcelExport file = service.exportExcel(principal, tab, q, sort, order, categoryId);
      return ResponseEntity.ok()
          .header(
              HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + file.filename() + "\"")
          .contentType(MediaType.parseMediaType(file.contentType()))
          .body(file.bytes());
    }
    ListPage result = service.list(principal, tab, q, sort, order, page, limit, categoryId);
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("success", true);
    body.put("data", result.data());
    body.put("meta", result.meta());
    return body;
  }

  @GetMapping("/summary")
  @Operation(summary = "Inventory KPI summary")
  public ApiResponse<Map<String, Object>> summary(
      @AuthenticationPrincipal MedmatePrincipal principal) {
    return ApiResponse.ok(service.summary(principal));
  }

  @GetMapping("/{productId}")
  @Operation(summary = "Product inventory detail")
  public ApiResponse<Map<String, Object>> get(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @PathVariable("productId") UUID productId) {
    return ApiResponse.ok(service.get(principal, productId));
  }

  @PatchMapping("/{productId}")
  @Operation(summary = "Update product settings (visibility, reorder, rack)")
  public ApiResponse<Map<String, Object>> patchSettings(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @PathVariable("productId") UUID productId,
      @RequestBody(required = false) SettingsRequest body) {
    SettingsRequest req = body == null ? new SettingsRequest(null, null, null, null) : body;
    return ApiResponse.ok(
        service.patchSettings(
            principal,
            productId,
            req.isLooseSellingEnabled(),
            req.isOnlineVisible(),
            req.reorderLevel(),
            req.rackLocationCode()));
  }

  @PatchMapping("/{productId}/rack")
  @Operation(summary = "Add or remove a rack location on a product")
  public ApiResponse<Map<String, Object>> patchRack(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @PathVariable("productId") UUID productId,
      @RequestBody(required = false) RackPatchRequest body) {
    RackPatchRequest req = body == null ? new RackPatchRequest(null, null) : body;
    return ApiResponse.ok(
        rackLocationService.patchProductRack(principal, productId, req.rackCode(), req.action()));
  }

  @PatchMapping("/{productId}/details")
  @Operation(summary = "Edit product master info")
  public ApiResponse<Map<String, Object>> patchDetails(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @PathVariable("productId") UUID productId,
      @RequestBody(required = false) DetailsRequest body) {
    DetailsRequest req =
        body == null
            ? new DetailsRequest(
                null, null, null, null, null, null, null, null, null, null, null, null)
            : body;
    return ApiResponse.ok(
        service.patchDetails(
            principal,
            productId,
            req.name(),
            req.saltComposition(),
            req.manufacturer(),
            req.packSize(),
            req.packUnit(),
            req.categoryId(),
            req.form(),
            req.schedule(),
            req.hsnCode(),
            req.gstPct(),
            req.rackLocations(),
            req.productPhotoUrl()));
  }

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record SettingsRequest(
      Boolean isLooseSellingEnabled,
      Boolean isOnlineVisible,
      Integer reorderLevel,
      String rackLocationCode) {}

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record RackPatchRequest(String rackCode, String action) {}

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record DetailsRequest(
      String name,
      String saltComposition,
      String manufacturer,
      Integer packSize,
      String packUnit,
      UUID categoryId,
      String form,
      String schedule,
      String hsnCode,
      BigDecimal gstPct,
      List<String> rackLocations,
      String productPhotoUrl) {
    public DetailsRequest {
      rackLocations = rackLocations == null ? null : List.copyOf(rackLocations);
    }
  }
}
