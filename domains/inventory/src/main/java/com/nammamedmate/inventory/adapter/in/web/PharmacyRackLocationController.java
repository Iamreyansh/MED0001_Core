package com.nammamedmate.inventory.adapter.in.web;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.nammamedmate.inventory.application.RackLocationService;
import com.nammamedmate.inventory.application.RackLocationService.PageResult;
import com.nammamedmate.kernel.api.ApiResponse;
import com.nammamedmate.security.MedmatePrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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
@RequestMapping("/api/v1/pharmacy/rack-locations")
@Tag(name = "Pharmacy rack locations")
public class PharmacyRackLocationController {

  private final RackLocationService service;

  public PharmacyRackLocationController(RackLocationService service) {
    this.service = service;
  }

  @GetMapping
  @Operation(summary = "List rack locations with KPI")
  public Map<String, Object> list(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @RequestParam(value = "zone", required = false) String zone,
      @RequestParam(value = "q", required = false) String q,
      @RequestParam(value = "page", required = false) Integer page,
      @RequestParam(value = "limit", required = false) Integer limit) {
    PageResult result = service.list(principal, zone, q, page, limit);
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("success", true);
    body.put("data", result.data());
    body.put("meta", result.meta());
    return body;
  }

  @PostMapping
  @Operation(summary = "Create a rack location (owner)")
  public ResponseEntity<ApiResponse<Map<String, Object>>> create(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @RequestBody(required = false) CreateRequest body) {
    CreateRequest req = body == null ? new CreateRequest(null, null, null) : body;
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(
            ApiResponse.ok(
                service.create(principal, req.rackCode(), req.zoneName(), req.description())));
  }

  @GetMapping("/unlocated")
  @Operation(summary = "List products with empty rack_locations")
  public Map<String, Object> unlocated(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @RequestParam(value = "page", required = false) Integer page,
      @RequestParam(value = "limit", required = false) Integer limit) {
    PageResult result = service.unlocated(principal, page, limit);
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("success", true);
    body.put("data", result.data());
    body.put("meta", result.meta());
    return body;
  }

  @PostMapping("/assign")
  @Operation(summary = "Bulk assign rack to products (idempotent)")
  public ApiResponse<Map<String, Object>> assign(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @RequestBody(required = false) AssignRequest body) {
    AssignRequest req = body == null ? new AssignRequest(null, null) : body;
    return ApiResponse.ok(service.assign(principal, req.productIds(), req.rackCode()));
  }

  @PostMapping("/print-labels")
  @Operation(summary = "Generate rack label PDF (data URL)")
  public ApiResponse<Map<String, Object>> printLabels(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @RequestBody(required = false) PrintLabelsRequest body) {
    PrintLabelsRequest req = body == null ? new PrintLabelsRequest(null) : body;
    return ApiResponse.ok(service.printLabels(principal, req.rackCodes()));
  }

  @GetMapping("/{rackCode}")
  @Operation(summary = "Rack detail with medicines")
  public ApiResponse<Map<String, Object>> detail(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @PathVariable("rackCode") String rackCode) {
    return ApiResponse.ok(service.detail(principal, rackCode));
  }

  @DeleteMapping("/{rackCode}")
  @Operation(summary = "Delete empty rack (owner)")
  public ApiResponse<Map<String, Object>> delete(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @PathVariable("rackCode") String rackCode) {
    return ApiResponse.ok(service.delete(principal, rackCode));
  }

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record CreateRequest(String rackCode, String zoneName, String description) {}

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record AssignRequest(List<UUID> productIds, String rackCode) {
    public AssignRequest {
      productIds = productIds == null ? null : List.copyOf(productIds);
    }
  }

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record PrintLabelsRequest(List<String> rackCodes) {
    public PrintLabelsRequest {
      rackCodes = rackCodes == null ? null : List.copyOf(rackCodes);
    }
  }
}
