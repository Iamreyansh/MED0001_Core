package com.nammamedmate.inventory.adapter.in.web;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.nammamedmate.inventory.application.DistributorService;
import com.nammamedmate.inventory.application.DistributorService.ListPage;
import com.nammamedmate.kernel.api.ApiResponse;
import com.nammamedmate.security.MedmatePrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/pharmacy/distributors")
@Tag(name = "Pharmacy distributors")
public class PharmacyDistributorController {

  private final DistributorService service;

  public PharmacyDistributorController(DistributorService service) {
    this.service = service;
  }

  @GetMapping
  @Operation(summary = "List distributors with KPI")
  public Map<String, Object> list(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @RequestParam(value = "is_active", required = false) Boolean isActive,
      @RequestParam(value = "q", required = false) String q,
      @RequestParam(value = "page", required = false) Integer page,
      @RequestParam(value = "limit", required = false) Integer limit) {
    ListPage result = service.list(principal, isActive, q, page, limit);
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("success", true);
    body.put("data", result.data());
    body.put("meta", result.meta());
    return body;
  }

  /** Literal path must be registered before /{id}. */
  @GetMapping("/price-compare")
  @Operation(summary = "Cross-distributor price comparison")
  public Map<String, Object> priceCompare(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @RequestParam(value = "only_multi_source", required = false) Boolean onlyMultiSource,
      @RequestParam(value = "q", required = false) String q,
      @RequestParam(value = "page", required = false) Integer page,
      @RequestParam(value = "limit", required = false) Integer limit) {
    ListPage result = service.priceCompare(principal, onlyMultiSource, q, page, limit);
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("success", true);
    body.put("data", result.data());
    body.put("meta", result.meta());
    return body;
  }

  @PostMapping
  @Operation(summary = "Add distributor")
  public ResponseEntity<ApiResponse<Map<String, Object>>> create(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @RequestBody(required = false) DistributorRequest body) {
    DistributorRequest req =
        body == null
            ? new DistributorRequest(null, null, null, null, null, null, null, null, null, null)
            : body;
    Map<String, Object> data =
        service.create(
            principal,
            req.firmName(),
            req.contactName(),
            req.phone(),
            req.email(),
            req.gstin(),
            req.drugLicenceNumber(),
            req.address(),
            req.paymentTermsDays(),
            req.creditLimit(),
            req.isActive());
    return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(data));
  }

  @PatchMapping("/{id}")
  @Operation(summary = "Update distributor")
  public ApiResponse<Map<String, Object>> patch(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @PathVariable("id") UUID id,
      @RequestBody(required = false) DistributorRequest body) {
    DistributorRequest req =
        body == null
            ? new DistributorRequest(null, null, null, null, null, null, null, null, null, null)
            : body;
    return ApiResponse.ok(
        service.patch(
            principal,
            id,
            req.firmName(),
            req.contactName(),
            req.phone(),
            req.email(),
            req.gstin(),
            req.drugLicenceNumber(),
            req.address(),
            req.paymentTermsDays(),
            req.creditLimit(),
            req.isActive()));
  }

  @DeleteMapping("/{id}")
  @Operation(summary = "Deactivate distributor (soft)")
  public ApiResponse<Map<String, Object>> deactivate(
      @AuthenticationPrincipal MedmatePrincipal principal, @PathVariable("id") UUID id) {
    return ApiResponse.ok(service.deactivate(principal, id));
  }

  @GetMapping("/{id}/supply-list")
  @Operation(summary = "Distributor supply list")
  public Map<String, Object> supplyList(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @PathVariable("id") UUID id,
      @RequestParam(value = "q", required = false) String q,
      @RequestParam(value = "page", required = false) Integer page,
      @RequestParam(value = "limit", required = false) Integer limit) {
    ListPage result = service.supplyList(principal, id, q, page, limit);
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("success", true);
    body.put("data", result.data());
    body.put("meta", result.meta());
    return body;
  }

  @PatchMapping("/{id}/supply-list/{productId}/set-preferred")
  @Operation(summary = "Set preferred distributor for a product")
  public ApiResponse<Map<String, Object>> setPreferred(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @PathVariable("id") UUID id,
      @PathVariable("productId") UUID productId) {
    return ApiResponse.ok(service.setPreferred(principal, id, productId));
  }

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record DistributorRequest(
      String firmName,
      String contactName,
      String phone,
      String email,
      String gstin,
      String drugLicenceNumber,
      String address,
      Integer paymentTermsDays,
      BigDecimal creditLimit,
      Boolean isActive) {}
}
