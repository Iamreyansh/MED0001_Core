package com.nammamedmate.integration.adapter.in.web;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.nammamedmate.integration.application.EinvoiceService;
import com.nammamedmate.integration.application.InternalServiceAuth;
import com.nammamedmate.kernel.api.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "E-Invoicing IRN")
public class EinvoiceIntegrationController {

  private final EinvoiceService service;
  private final InternalServiceAuth internalAuth;

  public EinvoiceIntegrationController(EinvoiceService service, InternalServiceAuth internalAuth) {
    this.service = service;
    this.internalAuth = internalAuth;
  }

  @PostMapping("/api/v1/integrations/einvoice/generate-irn")
  @Operation(summary = "Generate IRN via GSP/NIC (internal token)")
  public ApiResponse<Map<String, Object>> generateIrn(
      @RequestHeader(value = "X-Internal-Token", required = false) String internalToken,
      @RequestBody(required = false) GenerateIrnRequest body) {
    internalAuth.require(internalToken);
    GenerateIrnRequest req = body == null ? new GenerateIrnRequest(null, null, null) : body;
    return ApiResponse.ok(
        service.generateIrn(req.pharmacyId(), req.platformInvoiceId(), req.invoiceData()));
  }

  @PostMapping("/api/v1/integrations/einvoice/cancel-irn")
  @Operation(summary = "Cancel IRN within 24h (internal token)")
  public ApiResponse<Map<String, Object>> cancelIrn(
      @RequestHeader(value = "X-Internal-Token", required = false) String internalToken,
      @RequestBody(required = false) CancelIrnRequest body) {
    internalAuth.require(internalToken);
    CancelIrnRequest req = body == null ? new CancelIrnRequest(null, null, null) : body;
    return ApiResponse.ok(service.cancelIrn(req.irn(), req.cancelReasonCode(), req.cancelRemark()));
  }

  @GetMapping("/api/v1/integrations/einvoice/status/{irn}")
  @Operation(summary = "IRN status (internal token)")
  public ApiResponse<Map<String, Object>> status(
      @RequestHeader(value = "X-Internal-Token", required = false) String internalToken,
      @PathVariable("irn") String irn) {
    internalAuth.require(internalToken);
    return ApiResponse.ok(service.status(irn));
  }

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record GenerateIrnRequest(
      UUID pharmacyId, UUID platformInvoiceId, Map<String, Object> invoiceData) {
    public GenerateIrnRequest {
      invoiceData = invoiceData == null ? null : Map.copyOf(invoiceData);
    }
  }

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record CancelIrnRequest(String irn, String cancelReasonCode, String cancelRemark) {}
}
