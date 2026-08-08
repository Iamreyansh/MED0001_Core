package com.nammamedmate.order.adapter.in.web;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.nammamedmate.kernel.api.ApiResponse;
import com.nammamedmate.order.application.RxQuoteBroadcastService;
import com.nammamedmate.security.MedmatePrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/orders/rx-quote")
@Tag(name = "Rx quote broadcast (customer)")
public class RxQuoteCustomerController {

  private final RxQuoteBroadcastService service;

  public RxQuoteCustomerController(RxQuoteBroadcastService service) {
    this.service = service;
  }

  @PostMapping("/broadcast")
  @ResponseStatus(HttpStatus.CREATED)
  @Operation(summary = "Broadcast prescription to nearby pharmacies")
  public ApiResponse<Map<String, Object>> broadcast(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @RequestBody(required = false) BroadcastRequest body) {
    BroadcastRequest req = body == null ? new BroadcastRequest(null, null, null, null) : body;
    return ApiResponse.ok(
        service.broadcast(
            principal,
            req.prescriptionId(),
            req.deliveryAddressId(),
            req.patientName(),
            req.notes()));
  }

  @GetMapping("/{broadcastId}")
  @Operation(summary = "Get broadcast status (poll)")
  public ApiResponse<Map<String, Object>> get(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @PathVariable("broadcastId") UUID broadcastId) {
    return ApiResponse.ok(service.getBroadcast(principal, broadcastId));
  }

  @GetMapping("/{broadcastId}/quotes")
  @Operation(summary = "List received quotes")
  public ApiResponse<List<Map<String, Object>>> quotes(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @PathVariable("broadcastId") UUID broadcastId) {
    return ApiResponse.ok(service.listQuotes(principal, broadcastId));
  }

  @PostMapping("/{broadcastId}/select")
  @Operation(summary = "Select a quote and create cart")
  public ApiResponse<Map<String, Object>> select(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @PathVariable("broadcastId") UUID broadcastId,
      @RequestBody(required = false) SelectRequest body) {
    SelectRequest req = body == null ? new SelectRequest(null) : body;
    return ApiResponse.ok(service.selectQuote(principal, broadcastId, req.pharmacyId()));
  }

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record BroadcastRequest(
      UUID prescriptionId, UUID deliveryAddressId, String patientName, String notes) {}

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record SelectRequest(UUID pharmacyId) {}
}
