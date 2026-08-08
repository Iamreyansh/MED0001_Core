package com.nammamedmate.order.adapter.in.web;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.nammamedmate.kernel.api.ApiResponse;
import com.nammamedmate.order.application.CartService;
import com.nammamedmate.security.MedmatePrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/cart")
@Tag(name = "Customer cart")
public class CartController {

  private final CartService cartService;

  public CartController(CartService cartService) {
    this.cartService = cartService;
  }

  @GetMapping
  @Operation(summary = "Get current active cart")
  public ApiResponse<Map<String, Object>> get(@AuthenticationPrincipal MedmatePrincipal principal) {
    return ApiResponse.ok(cartService.getCart(principal));
  }

  @PostMapping("/items")
  @Operation(summary = "Add item to cart (smart-select on first add)")
  public ApiResponse<Map<String, Object>> addItem(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @RequestBody(required = false) AddItemRequest body) {
    AddItemRequest req = body == null ? new AddItemRequest(null, null, null, null, null) : body;
    return ApiResponse.ok(
        cartService.addItem(
            principal,
            req.medicineId(),
            req.quantity(),
            req.switchPharmacy(),
            req.lat(),
            req.lng()));
  }

  @PatchMapping("/items/{itemId}")
  @Operation(summary = "Update cart item quantity (0 removes)")
  public ApiResponse<Map<String, Object>> updateItem(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @PathVariable("itemId") UUID itemId,
      @RequestBody(required = false) UpdateQtyRequest body) {
    UpdateQtyRequest req = body == null ? new UpdateQtyRequest(null) : body;
    return ApiResponse.ok(cartService.updateItemQuantity(principal, itemId, req.quantity()));
  }

  @DeleteMapping("/items/{itemId}")
  @Operation(summary = "Remove cart item")
  public ApiResponse<Map<String, Object>> removeItem(
      @AuthenticationPrincipal MedmatePrincipal principal, @PathVariable("itemId") UUID itemId) {
    return ApiResponse.ok(cartService.removeItem(principal, itemId));
  }

  @DeleteMapping
  @Operation(summary = "Clear cart")
  public ApiResponse<Map<String, Object>> clear(
      @AuthenticationPrincipal MedmatePrincipal principal) {
    return ApiResponse.ok(cartService.clearCart(principal));
  }

  @PostMapping("/coupon")
  @Operation(summary = "Apply coupon")
  public ApiResponse<Map<String, Object>> applyCoupon(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @RequestBody(required = false) CouponRequest body) {
    CouponRequest req = body == null ? new CouponRequest(null) : body;
    return ApiResponse.ok(cartService.applyCoupon(principal, req.couponCode()));
  }

  @DeleteMapping("/coupon")
  @Operation(summary = "Remove coupon")
  public ApiResponse<Map<String, Object>> removeCoupon(
      @AuthenticationPrincipal MedmatePrincipal principal) {
    return ApiResponse.ok(cartService.removeCoupon(principal));
  }

  @PostMapping("/prescription")
  @Operation(summary = "Attach prescription")
  public ApiResponse<Map<String, Object>> attachPrescription(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @RequestBody(required = false) PrescriptionRequest body) {
    PrescriptionRequest req = body == null ? new PrescriptionRequest(null) : body;
    return ApiResponse.ok(cartService.attachPrescription(principal, req.prescriptionId()));
  }

  @DeleteMapping("/prescription")
  @Operation(summary = "Remove prescription")
  public ApiResponse<Map<String, Object>> removePrescription(
      @AuthenticationPrincipal MedmatePrincipal principal) {
    return ApiResponse.ok(cartService.removePrescription(principal));
  }

  @PostMapping("/address")
  @Operation(summary = "Set delivery address")
  public ApiResponse<Map<String, Object>> setAddress(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @RequestBody(required = false) AddressRequest body) {
    AddressRequest req = body == null ? new AddressRequest(null) : body;
    return ApiResponse.ok(cartService.setAddress(principal, req.addressId()));
  }

  @PostMapping("/switch-pharmacy")
  @Operation(summary = "Switch pharmacy (clears items)")
  public ApiResponse<Map<String, Object>> switchPharmacy(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @RequestBody(required = false) SwitchPharmacyRequest body) {
    SwitchPharmacyRequest req = body == null ? new SwitchPharmacyRequest(null, null) : body;
    return ApiResponse.ok(cartService.switchPharmacy(principal, req.pharmacyId(), req.confirm()));
  }

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record AddItemRequest(
      UUID medicineId, Integer quantity, Boolean switchPharmacy, Double lat, Double lng) {}

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record UpdateQtyRequest(Integer quantity) {}

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record CouponRequest(String couponCode) {}

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record PrescriptionRequest(UUID prescriptionId) {}

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record AddressRequest(UUID addressId) {}

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record SwitchPharmacyRequest(UUID pharmacyId, Boolean confirm) {}
}
