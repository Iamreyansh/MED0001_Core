package com.nammamedmate.marketing.adapter.in.web;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.nammamedmate.kernel.api.ApiResponse;
import com.nammamedmate.marketing.application.CouponService;
import com.nammamedmate.security.MedmatePrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/coupons")
@Tag(name = "Customer coupons")
public class CustomerCouponController {

  private final CouponService coupons;

  public CustomerCouponController(CouponService coupons) {
    this.coupons = coupons;
  }

  @PostMapping("/validate")
  @Operation(summary = "Validate coupon for cart")
  public ApiResponse<Map<String, Object>> validate(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @RequestBody(required = false) ValidateRequest body) {
    ValidateRequest req =
        body == null ? new ValidateRequest(null, null, null, null, null, null) : body;
    return ApiResponse.ok(
        coupons.validate(
            principal,
            new CouponService.ValidateCommand(
                req.couponCode(),
                req.cartTotal(),
                req.customerId(),
                req.isFirstOrder(),
                req.hasRxItems(),
                req.pharmacyId())));
  }

  @GetMapping("/available")
  @Operation(summary = "List available coupons")
  public ApiResponse<Map<String, Object>> available(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @RequestParam(name = "include_applied", required = false) Boolean includeApplied) {
    CouponService.PagedResult result = coupons.available(principal, includeApplied);
    return ApiResponse.ok(result.data(), result.meta());
  }

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record ValidateRequest(
      String couponCode,
      Number cartTotal,
      UUID customerId,
      Boolean isFirstOrder,
      Boolean hasRxItems,
      UUID pharmacyId) {}
}
