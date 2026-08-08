package com.nammamedmate.rider.adapter.in.web;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.nammamedmate.kernel.api.ApiResponse;
import com.nammamedmate.rider.application.RiderKycService;
import com.nammamedmate.security.MedmatePrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.io.IOException;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/rider/kyc")
@Tag(name = "Rider KYC")
public class RiderKycController {

  private final RiderKycService service;

  public RiderKycController(RiderKycService service) {
    this.service = service;
  }

  /**
   * POST /api/v1/rider/kyc/documents — upload a KYC document.
   *
   * <p>ponytail: story multipart contract (same waiver as pharmacy KYC); migrate to client-side S3
   * presign PUT later. Ceiling: ≤10 MB through API/Lambda.
   */
  @PostMapping(value = "/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  @Operation(summary = "Upload a rider KYC document")
  public ApiResponse<Map<String, Object>> upload(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @RequestParam("document_type") String documentType,
      @RequestParam("file") MultipartFile file,
      @RequestParam(name = "expiry_date", required = false) String expiryDate,
      @RequestParam(name = "document_number", required = false) String documentNumber)
      throws IOException {
    return ApiResponse.ok(
        service.uploadDocument(
            principal,
            documentType,
            file.getBytes(),
            file.getContentType(),
            expiryDate,
            documentNumber));
  }

  @PostMapping("/submit")
  @Operation(summary = "Submit rider KYC for admin review")
  public ApiResponse<Map<String, Object>> submit(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @RequestBody(required = false) EmptyBody body) {
    return ApiResponse.ok(service.submitKyc(principal));
  }

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record EmptyBody() {}
}
