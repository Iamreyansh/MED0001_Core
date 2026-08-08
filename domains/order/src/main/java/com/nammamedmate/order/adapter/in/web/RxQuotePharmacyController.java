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
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/pharmacy/rx-quotes")
@Tag(name = "Rx quote broadcast (pharmacy)")
public class RxQuotePharmacyController {

  private final RxQuoteBroadcastService service;

  public RxQuotePharmacyController(RxQuoteBroadcastService service) {
    this.service = service;
  }

  @GetMapping
  @Operation(summary = "List incoming Rx quote broadcasts")
  public ApiResponse<List<Map<String, Object>>> list(
      @AuthenticationPrincipal MedmatePrincipal principal) {
    return ApiResponse.ok(service.listIncoming(principal));
  }

  @PostMapping("/{broadcastId}/quote")
  @Operation(summary = "Submit a quote for a broadcast")
  public ApiResponse<Map<String, Object>> quote(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @PathVariable("broadcastId") UUID broadcastId,
      @RequestBody(required = false) QuoteRequest body) {
    QuoteRequest req = body == null ? new QuoteRequest(null, null) : body;
    return ApiResponse.ok(
        service.submitQuote(
            principal, broadcastId, req.medicinesAvailable(), req.deliveryEtaMinutes()));
  }

  @PostMapping("/{broadcastId}/decline")
  @Operation(summary = "Decline a broadcast")
  public ApiResponse<Map<String, Object>> decline(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @PathVariable("broadcastId") UUID broadcastId,
      @RequestBody(required = false) DeclineRequest body) {
    DeclineRequest req = body == null ? new DeclineRequest(null) : body;
    return ApiResponse.ok(service.decline(principal, broadcastId, req.reason()));
  }

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record QuoteRequest(
      List<Map<String, Object>> medicinesAvailable, Integer deliveryEtaMinutes) {
    public QuoteRequest {
      medicinesAvailable = medicinesAvailable == null ? null : List.copyOf(medicinesAvailable);
    }
  }

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record DeclineRequest(String reason) {}
}
