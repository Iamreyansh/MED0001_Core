package com.nammamedmate.pos.adapter.in.web;

import com.nammamedmate.kernel.api.ApiResponse;
import com.nammamedmate.pos.application.OfferService;
import com.nammamedmate.security.MedmatePrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
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
@RequestMapping("/api/v1/pharmacy/offers")
@Tag(name = "Pharmacy Offers")
public class PharmacyOfferController {

  private final OfferService offerService;

  public PharmacyOfferController(OfferService offerService) {
    this.offerService = offerService;
  }

  @GetMapping
  @Operation(summary = "List pharmacy offers with KPI")
  public Map<String, Object> list(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @RequestParam(value = "status", required = false) String status,
      @RequestParam(value = "page", required = false) Integer page,
      @RequestParam(value = "limit", required = false) Integer limit) {
    OfferService.ListResult result = offerService.list(principal, status, page, limit);
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("success", true);
    body.put("data", result.data());
    body.put("meta", result.meta());
    return body;
  }

  @PostMapping
  @Operation(summary = "Create pharmacy offer (owner)")
  public ResponseEntity<ApiResponse<Map<String, Object>>> create(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @RequestBody(required = false) Map<String, Object> body) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(ApiResponse.ok(offerService.create(principal, body)));
  }

  /** Literal path before /{offerId}. */
  @PostMapping("/validate")
  @Operation(summary = "Validate coupon code against cart")
  public ApiResponse<Map<String, Object>> validate(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @RequestBody(required = false) Map<String, Object> body) {
    return ApiResponse.ok(offerService.validate(principal, body));
  }

  @PatchMapping("/{offerId}")
  @Operation(summary = "Update pharmacy offer (owner)")
  public ApiResponse<Map<String, Object>> update(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @PathVariable UUID offerId,
      @RequestBody(required = false) Map<String, Object> body) {
    return ApiResponse.ok(offerService.update(principal, offerId, body));
  }

  @PatchMapping("/{offerId}/toggle")
  @Operation(summary = "Toggle offer active flag (owner)")
  public ApiResponse<Map<String, Object>> toggle(
      @AuthenticationPrincipal MedmatePrincipal principal, @PathVariable UUID offerId) {
    return ApiResponse.ok(offerService.toggle(principal, offerId));
  }

  @DeleteMapping("/{offerId}")
  @Operation(summary = "Delete or expire pharmacy offer (owner)")
  public ApiResponse<Map<String, Object>> delete(
      @AuthenticationPrincipal MedmatePrincipal principal, @PathVariable UUID offerId) {
    return ApiResponse.ok(offerService.delete(principal, offerId));
  }
}
