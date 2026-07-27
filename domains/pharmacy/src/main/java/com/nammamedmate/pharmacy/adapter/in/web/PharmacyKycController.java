package com.nammamedmate.pharmacy.adapter.in.web;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.nammamedmate.kernel.api.ApiResponse;
import com.nammamedmate.pharmacy.application.PharmacyKycService;
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
@RequestMapping("/api/v1/pharmacy/kyc")
@Tag(name = "Pharmacy KYC documents")
public class PharmacyKycController {

  private final PharmacyKycService service;

  public PharmacyKycController(PharmacyKycService service) {
    this.service = service;
  }

  /**
   * POST /api/v1/pharmacy/kyc/documents — upload a KYC document. ponytail: story multipart
   * contract; migrate to client-side presign PUT later.
   */
  @PostMapping(value = "/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  @Operation(summary = "Upload a KYC document file")
  public ResponseEntity<ApiResponse<Map<String, Object>>> uploadDocument(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @RequestParam("document_type") String documentType,
      @RequestParam("file") MultipartFile file,
      @RequestParam(name = "expiry_date", required = false) String expiryDate)
      throws IOException {
    byte[] bytes = file.getBytes();
    Map<String, Object> data =
        service.uploadDocument(
            principal,
            documentType,
            bytes,
            file.getOriginalFilename(),
            file.getContentType(),
            expiryDate);
    return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(data));
  }

  @GetMapping("/documents")
  @Operation(summary = "List KYC documents for the authenticated pharmacy")
  public ApiResponse<Map<String, Object>> listDocuments(
      @AuthenticationPrincipal MedmatePrincipal principal) {
    return ApiResponse.ok(service.listDocuments(principal));
  }

  @DeleteMapping("/documents/{documentId}")
  @Operation(summary = "Soft-delete a KYC document (UPLOADED or REJECTED only)")
  public ApiResponse<Map<String, Object>> deleteDocument(
      @AuthenticationPrincipal MedmatePrincipal principal, @PathVariable UUID documentId) {
    return ApiResponse.ok(service.deleteDocument(principal, documentId));
  }

  @PostMapping("/submit")
  @Operation(summary = "Submit all uploaded KYC documents for admin review")
  public ApiResponse<Map<String, Object>> submitKyc(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @RequestBody(required = false) EmptyBody body) {
    return ApiResponse.ok(service.submitKyc(principal));
  }

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record EmptyBody() {}
}
