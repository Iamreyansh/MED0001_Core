package com.nammamedmate.marketing.adapter.in.web;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.nammamedmate.kernel.api.ApiResponse;
import com.nammamedmate.marketing.application.CouponService;
import com.nammamedmate.security.MedmatePrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/coupons")
@Tag(name = "Admin coupons")
public class AdminCouponController {

  private final CouponService coupons;

  public AdminCouponController(CouponService coupons) {
    this.coupons = coupons;
  }

  @GetMapping
  @Operation(summary = "List coupons")
  public ApiResponse<Map<String, Object>> list(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String type,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer limit,
      @RequestParam(required = false) String sort,
      @RequestParam(required = false) String order) {
    CouponService.PagedResult result =
        coupons.list(principal, status, type, page, limit, sort, order);
    return ApiResponse.ok(result.data(), result.meta());
  }

  @PostMapping
  @Operation(summary = "Create coupon")
  public ResponseEntity<ApiResponse<Map<String, Object>>> create(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @RequestBody(required = false) CreateCouponRequest body) {
    CreateCouponRequest req =
        body == null
            ? new CreateCouponRequest(
                null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                null)
            : body;
    Map<String, Object> data =
        coupons.create(
            principal,
            new CouponService.CreateCommand(
                req.code(),
                req.type(),
                req.value(),
                req.minOrderValue(),
                req.maxDiscountCap(),
                req.maxRedemptionsTotal(),
                req.maxPerUser(),
                req.budgetTotal(),
                req.segmentIds(),
                req.isFirstOrderOnly(),
                req.isRxOrdersOnly(),
                req.validFrom(),
                req.validUntil(),
                req.description(),
                req.terms()));
    return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(data));
  }

  @GetMapping("/{code}")
  @Operation(summary = "Get coupon detail")
  public ApiResponse<Map<String, Object>> get(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @PathVariable("code") String code,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer limit) {
    return ApiResponse.ok(coupons.get(principal, code, page, limit));
  }

  @PatchMapping("/{code}")
  @Operation(summary = "Update coupon")
  public ApiResponse<Map<String, Object>> patch(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @PathVariable("code") String code,
      @RequestBody(required = false) PatchCouponRequest body) {
    PatchCouponRequest req =
        body == null
            ? new PatchCouponRequest(
                null, null, null, null, null, null, null, null, null, null, null, null, null, null)
            : body;
    boolean immutable = req.code() != null || req.type() != null;
    return ApiResponse.ok(
        coupons.patch(
            principal,
            code,
            new CouponService.PatchCommand(
                req.minOrderValue(),
                req.maxDiscountCap(),
                req.budgetTotal(),
                req.maxRedemptionsTotal(),
                req.maxPerUser(),
                req.segmentIds(),
                req.isFirstOrderOnly(),
                req.isRxOrdersOnly(),
                req.validFrom(),
                req.validUntil(),
                req.description(),
                req.terms(),
                immutable)));
  }

  @PatchMapping("/{code}/toggle")
  @Operation(summary = "Toggle coupon ACTIVE/PAUSED")
  public ApiResponse<Map<String, Object>> toggle(
      @AuthenticationPrincipal MedmatePrincipal principal, @PathVariable("code") String code) {
    return ApiResponse.ok(coupons.toggle(principal, code));
  }

  @DeleteMapping("/{code}")
  @Operation(summary = "Delete or expire coupon")
  public ApiResponse<Map<String, Object>> delete(
      @AuthenticationPrincipal MedmatePrincipal principal, @PathVariable("code") String code) {
    return ApiResponse.ok(coupons.delete(principal, code));
  }

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record CreateCouponRequest(
      String code,
      String type,
      Number value,
      Number minOrderValue,
      Number maxDiscountCap,
      Integer maxRedemptionsTotal,
      Integer maxPerUser,
      Number budgetTotal,
      List<UUID> segmentIds,
      Boolean isFirstOrderOnly,
      Boolean isRxOrdersOnly,
      Instant validFrom,
      Instant validUntil,
      String description,
      String terms) {}

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record PatchCouponRequest(
      String code,
      String type,
      Number minOrderValue,
      Number maxDiscountCap,
      Number budgetTotal,
      Integer maxRedemptionsTotal,
      Integer maxPerUser,
      List<UUID> segmentIds,
      Boolean isFirstOrderOnly,
      Boolean isRxOrdersOnly,
      Instant validFrom,
      Instant validUntil,
      String description,
      String terms) {}
}
