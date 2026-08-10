package com.nammamedmate.prescription.adapter.in.web;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.nammamedmate.kernel.api.ApiResponse;
import com.nammamedmate.prescription.application.PrescriptionService;
import com.nammamedmate.prescription.application.PrescriptionService.ListResult;
import com.nammamedmate.security.MedmatePrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
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
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/prescriptions")
@Tag(name = "Customer prescriptions")
public class CustomerPrescriptionController {

  private final PrescriptionService service;

  public CustomerPrescriptionController(PrescriptionService service) {
    this.service = service;
  }

  @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  @Operation(summary = "Upload a prescription file (PDF/JPG/PNG ≤10 MB)")
  public ResponseEntity<ApiResponse<Map<String, Object>>> upload(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @RequestParam("file") MultipartFile file,
      @RequestParam(name = "patient_name", required = false) String patientName,
      @RequestParam(name = "notes", required = false) String notes)
      throws IOException {
    Map<String, Object> data =
        service.upload(principal, file.getBytes(), file.getContentType(), patientName, notes);
    return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(data));
  }

  @GetMapping
  @Operation(summary = "List customer prescriptions")
  public ApiResponse<java.util.List<Map<String, Object>>> list(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @RequestParam(name = "status", required = false) String status,
      @RequestParam(name = "type", required = false) String type,
      @RequestParam(name = "page", required = false) Integer page,
      @RequestParam(name = "limit", required = false) Integer limit,
      @RequestParam(name = "sort", required = false) String sort,
      @RequestParam(name = "order", required = false) String order) {
    ListResult result = service.list(principal, status, type, page, limit, sort, order);
    return ApiResponse.ok(result.data(), result.meta());
  }

  @GetMapping("/{id}")
  @Operation(summary = "Get prescription detail with fresh signed URL")
  public ApiResponse<Map<String, Object>> get(
      @AuthenticationPrincipal MedmatePrincipal principal, @PathVariable UUID id) {
    return ApiResponse.ok(service.get(principal, id));
  }

  @DeleteMapping("/{id}")
  @Operation(summary = "Soft-delete an uploaded prescription")
  public ApiResponse<Map<String, Object>> delete(
      @AuthenticationPrincipal MedmatePrincipal principal, @PathVariable UUID id) {
    return ApiResponse.ok(service.delete(principal, id));
  }

  @PostMapping("/{id}/use-in-cart")
  @Operation(summary = "Attach prescription to an active cart")
  public ApiResponse<Map<String, Object>> useInCart(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @PathVariable UUID id,
      @RequestBody UseInCartRequest body) {
    UUID cartId = body == null ? null : body.cartId();
    return ApiResponse.ok(service.useInCart(principal, id, cartId));
  }

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record UseInCartRequest(UUID cartId) {}
}
