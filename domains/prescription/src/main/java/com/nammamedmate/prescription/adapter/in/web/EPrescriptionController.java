package com.nammamedmate.prescription.adapter.in.web;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.nammamedmate.kernel.api.ApiResponse;
import com.nammamedmate.prescription.application.EPrescriptionService;
import com.nammamedmate.security.MedmatePrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.net.URI;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/prescriptions/eprescriptions")
@Tag(name = "e-Prescriptions")
public class EPrescriptionController {

  private final EPrescriptionService service;

  public EPrescriptionController(EPrescriptionService service) {
    this.service = service;
  }

  @GetMapping("/{id}")
  @Operation(summary = "Get e-prescription detail with signature verification")
  public ApiResponse<Map<String, Object>> get(
      @AuthenticationPrincipal MedmatePrincipal principal, @PathVariable UUID id) {
    return ApiResponse.ok(service.get(principal, id));
  }

  @PostMapping("/{id}/link-to-cart")
  @Operation(summary = "Manually link e-prescription to an active cart")
  public ApiResponse<Map<String, Object>> linkToCart(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @PathVariable UUID id,
      @RequestBody(required = false) LinkBody body) {
    UUID cartId = body == null ? null : body.cartId();
    return ApiResponse.ok(service.linkToCart(principal, id, cartId));
  }

  @GetMapping("/{id}/download")
  @Operation(summary = "Download e-prescription PDF (302 to 15m signed URL)")
  public ResponseEntity<Void> download(
      @AuthenticationPrincipal MedmatePrincipal principal, @PathVariable UUID id) {
    String url = service.downloadUrl(principal, id);
    return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(url)).build();
  }

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record LinkBody(UUID cartId) {}
}
