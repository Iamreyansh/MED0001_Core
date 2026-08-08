package com.nammamedmate.rider.adapter.in.web;

import com.nammamedmate.kernel.api.ApiResponse;
import com.nammamedmate.rider.application.DeliveryPricingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/delivery")
@Tag(name = "Delivery fee estimate")
public class DeliveryFeeEstimateController {

  private final DeliveryPricingService service;

  public DeliveryFeeEstimateController(DeliveryPricingService service) {
    this.service = service;
  }

  @GetMapping("/fee-estimate")
  @Operation(summary = "Public delivery fee estimate (rate-limited 30/min/IP)")
  public ApiResponse<Map<String, Object>> feeEstimate(
      HttpServletRequest request,
      @RequestParam(name = "pharmacy_id") UUID pharmacyId,
      @RequestParam(name = "delivery_address_id", required = false) UUID deliveryAddressId,
      @RequestParam(required = false) Double lat,
      @RequestParam(required = false) Double lng,
      @RequestParam(name = "order_value", required = false) BigDecimal orderValue) {
    return ApiResponse.ok(
        service.feeEstimate(
            clientIp(request), pharmacyId, deliveryAddressId, lat, lng, orderValue));
  }

  private static String clientIp(HttpServletRequest request) {
    String forwarded = request.getHeader("X-Forwarded-For");
    if (forwarded != null && !forwarded.isBlank()) {
      return forwarded.split(",")[0].trim();
    }
    String remote = request.getRemoteAddr();
    return remote == null ? "0.0.0.0" : remote;
  }
}
