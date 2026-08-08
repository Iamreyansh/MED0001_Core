package com.nammamedmate.rider.adapter.in.web;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.nammamedmate.kernel.api.ApiResponse;
import com.nammamedmate.rider.application.CodReconciliationService;
import com.nammamedmate.security.MedmatePrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/rider/cod")
@Tag(name = "Rider COD")
public class RiderCodController {

  private final CodReconciliationService service;

  public RiderCodController(CodReconciliationService service) {
    this.service = service;
  }

  @GetMapping
  @Operation(summary = "Rider: own COD summary")
  public ApiResponse<Map<String, Object>> summary(
      @AuthenticationPrincipal MedmatePrincipal principal) {
    return ApiResponse.ok(service.riderSummary(principal));
  }

  @PostMapping("/deposit-request")
  @Operation(summary = "Rider: submit COD deposit claim")
  public ApiResponse<Map<String, Object>> depositRequest(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @RequestBody(required = false) DepositRequest body) {
    return ApiResponse.ok(
        service.depositRequest(
            principal,
            body == null ? null : body.amount(),
            body == null ? null : body.depositMode(),
            body == null ? null : body.referenceNumber(),
            body == null ? null : body.notes()));
  }

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record DepositRequest(
      Object amount, String depositMode, String referenceNumber, String notes) {}
}
