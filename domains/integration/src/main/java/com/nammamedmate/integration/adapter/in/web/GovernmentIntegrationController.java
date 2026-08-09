package com.nammamedmate.integration.adapter.in.web;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.nammamedmate.integration.application.GovernmentApiService;
import com.nammamedmate.integration.application.InternalServiceAuth;
import com.nammamedmate.kernel.api.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "Government API integration")
public class GovernmentIntegrationController {

  private final GovernmentApiService service;
  private final InternalServiceAuth internalAuth;

  public GovernmentIntegrationController(
      GovernmentApiService service, InternalServiceAuth internalAuth) {
    this.service = service;
    this.internalAuth = internalAuth;
  }

  @PostMapping("/api/v1/integrations/gstn/verify")
  @Operation(summary = "Verify GSTIN (internal token)")
  public ApiResponse<Map<String, Object>> verifyGstin(
      @RequestHeader(value = "X-Internal-Token", required = false) String internalToken,
      @RequestBody(required = false) GstnVerifyRequest body) {
    internalAuth.require(internalToken);
    GstnVerifyRequest req = body == null ? new GstnVerifyRequest(null, null, null) : body;
    return ApiResponse.ok(service.verifyGstin(req.gstin(), req.entityType(), req.entityId()));
  }

  @PostMapping("/api/v1/integrations/digilocker/initiate")
  @Operation(summary = "Initiate DigiLocker OAuth (internal token)")
  public ApiResponse<Map<String, Object>> digiLockerInitiate(
      @RequestHeader(value = "X-Internal-Token", required = false) String internalToken,
      @RequestBody(required = false) DigiLockerInitiateRequest body) {
    internalAuth.require(internalToken);
    DigiLockerInitiateRequest req =
        body == null ? new DigiLockerInitiateRequest(null, null, null, null) : body;
    return ApiResponse.ok(
        service.initiateDigiLocker(req.phone(), req.purpose(), req.entityId(), req.redirectUri()));
  }

  @PostMapping("/api/v1/integrations/digilocker/callback")
  @Operation(summary = "DigiLocker OAuth callback (state-validated; no S2S token)")
  public ApiResponse<Map<String, Object>> digiLockerCallback(
      @RequestBody(required = false) DigiLockerCallbackRequest body) {
    DigiLockerCallbackRequest req = body == null ? new DigiLockerCallbackRequest(null, null) : body;
    return ApiResponse.ok(service.digiLockerCallback(req.code(), req.state()));
  }

  @PostMapping("/api/v1/integrations/drug-registry/verify-licence")
  @Operation(summary = "Verify drug licence (internal token); 202 when async")
  public ResponseEntity<ApiResponse<Map<String, Object>>> verifyDrugLicence(
      @RequestHeader(value = "X-Internal-Token", required = false) String internalToken,
      @RequestBody(required = false) DrugLicenceRequest body) {
    internalAuth.require(internalToken);
    DrugLicenceRequest req =
        body == null ? new DrugLicenceRequest(null, null, null, null, null) : body;
    Map<String, Object> data =
        service.verifyDrugLicence(
            req.licenceNumber(), req.state(), req.licenceType(), req.entityType(), req.entityId());
    if ("PENDING".equals(data.get("status")) && data.get("verification_id") != null) {
      return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResponse.ok(data));
    }
    return ResponseEntity.ok(ApiResponse.ok(data));
  }

  @GetMapping("/api/v1/integrations/drug-registry/verification/{id}")
  @Operation(summary = "Poll async drug licence verification (internal token)")
  public ApiResponse<Map<String, Object>> getDrugVerification(
      @RequestHeader(value = "X-Internal-Token", required = false) String internalToken,
      @PathVariable("id") UUID id) {
    internalAuth.require(internalToken);
    return ApiResponse.ok(service.getDrugVerification(id));
  }

  @PostMapping("/api/v1/integrations/fssai/verify")
  @Operation(summary = "Verify FSSAI licence (internal token)")
  public ApiResponse<Map<String, Object>> verifyFssai(
      @RequestHeader(value = "X-Internal-Token", required = false) String internalToken,
      @RequestBody(required = false) FssaiVerifyRequest body) {
    internalAuth.require(internalToken);
    FssaiVerifyRequest req = body == null ? new FssaiVerifyRequest(null, null, null) : body;
    return ApiResponse.ok(
        service.verifyFssai(req.licenceNumber(), req.entityType(), req.entityId()));
  }

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record GstnVerifyRequest(String gstin, String entityType, UUID entityId) {}

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record DigiLockerInitiateRequest(
      String phone, String purpose, UUID entityId, String redirectUri) {}

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record DigiLockerCallbackRequest(String code, String state) {}

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record DrugLicenceRequest(
      String licenceNumber, String state, String licenceType, String entityType, UUID entityId) {}

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record FssaiVerifyRequest(String licenceNumber, String entityType, UUID entityId) {}
}
