package com.nammamedmate.pos.adapter.in.web;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.nammamedmate.kernel.api.ApiResponse;
import com.nammamedmate.pos.application.PosCartService;
import com.nammamedmate.pos.application.PosCheckoutService;
import com.nammamedmate.security.MedmatePrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.math.BigDecimal;
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
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/pharmacy/pos")
@Tag(name = "Pharmacy POS")
public class PharmacyPosController {

  private final PosCartService cartService;
  private final PosCheckoutService checkoutService;

  public PharmacyPosController(PosCartService cartService, PosCheckoutService checkoutService) {
    this.cartService = cartService;
    this.checkoutService = checkoutService;
  }

  @PostMapping("/cart")
  @Operation(summary = "Create POS cart session")
  public ResponseEntity<ApiResponse<Map<String, Object>>> createCart(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @RequestBody(required = false) CreateCartRequest body) {
    UUID staff = body == null ? null : body.createdByStaffId();
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(ApiResponse.ok(cartService.createCart(principal, staff)));
  }

  @GetMapping("/cart/{cartId}")
  @Operation(summary = "Get POS cart state")
  public ApiResponse<Map<String, Object>> getCart(
      @AuthenticationPrincipal MedmatePrincipal principal, @PathVariable UUID cartId) {
    return ApiResponse.ok(cartService.getCart(principal, cartId));
  }

  @PostMapping("/cart/{cartId}/items")
  @Operation(summary = "Add item to POS cart")
  public ResponseEntity<ApiResponse<Map<String, Object>>> addItem(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @PathVariable UUID cartId,
      @RequestBody AddItemRequest body) {
    AddItemRequest req = body == null ? new AddItemRequest(null, null, null, null) : body;
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(
            ApiResponse.ok(
                cartService.addItem(
                    principal,
                    cartId,
                    req.productId(),
                    req.batchId(),
                    req.quantity(),
                    req.isLoose())));
  }

  @PatchMapping("/cart/{cartId}/items/{itemId}")
  @Operation(summary = "Update POS cart item")
  public ApiResponse<Map<String, Object>> updateItem(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @PathVariable UUID cartId,
      @PathVariable UUID itemId,
      @RequestBody(required = false) UpdateItemRequest body) {
    UpdateItemRequest req = body == null ? new UpdateItemRequest(null, null, null) : body;
    return ApiResponse.ok(
        cartService.updateItem(
            principal, cartId, itemId, req.quantity(), req.batchId(), req.isLoose()));
  }

  @DeleteMapping("/cart/{cartId}/items/{itemId}")
  @Operation(summary = "Remove POS cart item")
  public ApiResponse<Map<String, Object>> removeItem(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @PathVariable UUID cartId,
      @PathVariable UUID itemId) {
    return ApiResponse.ok(cartService.removeItem(principal, cartId, itemId));
  }

  @DeleteMapping("/cart/{cartId}")
  @Operation(summary = "Clear POS cart items")
  public ApiResponse<Map<String, Object>> clearCart(
      @AuthenticationPrincipal MedmatePrincipal principal, @PathVariable UUID cartId) {
    return ApiResponse.ok(cartService.clearCart(principal, cartId));
  }

  @PostMapping("/cart/{cartId}/search")
  @Operation(summary = "Search products for POS")
  public ApiResponse<Map<String, Object>> search(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @PathVariable UUID cartId,
      @RequestBody SearchRequest body) {
    SearchRequest req = body == null ? new SearchRequest(null, null) : body;
    return ApiResponse.ok(cartService.search(principal, cartId, req.query(), req.mode()));
  }

  @PostMapping("/cart/{cartId}/customer")
  @Operation(summary = "Attach customer to POS cart")
  public ApiResponse<Map<String, Object>> attachCustomer(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @PathVariable UUID cartId,
      @RequestBody CustomerRequest body) {
    CustomerRequest req = body == null ? new CustomerRequest(null, null) : body;
    return ApiResponse.ok(
        cartService.attachCustomer(principal, cartId, req.customerPhone(), req.customerName()));
  }

  @PostMapping("/cart/{cartId}/discount")
  @Operation(summary = "Apply discount to POS cart")
  public ApiResponse<Map<String, Object>> applyDiscount(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @PathVariable UUID cartId,
      @RequestBody DiscountRequest body) {
    DiscountRequest req = body == null ? new DiscountRequest(null, null) : body;
    return ApiResponse.ok(cartService.applyDiscount(principal, cartId, req.type(), req.value()));
  }

  @PostMapping("/cart/{cartId}/checkout")
  @Operation(summary = "Checkout POS cart")
  public ResponseEntity<ApiResponse<Map<String, Object>>> checkout(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @PathVariable UUID cartId,
      @RequestBody CheckoutRequest body) {
    CheckoutRequest req = body == null ? new CheckoutRequest(null, null, null, null) : body;
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(
            ApiResponse.ok(
                checkoutService.checkout(
                    principal,
                    cartId,
                    req.paymentMethod(),
                    req.amountPaid(),
                    req.upiReference(),
                    req.prescribingDoctor())));
  }

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record CreateCartRequest(UUID createdByStaffId) {}

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record AddItemRequest(UUID productId, UUID batchId, Integer quantity, Boolean isLoose) {}

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record UpdateItemRequest(Integer quantity, UUID batchId, Boolean isLoose) {}

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record SearchRequest(String query, String mode) {}

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record CustomerRequest(String customerPhone, String customerName) {}

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record DiscountRequest(String type, BigDecimal value) {}

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record CheckoutRequest(
      String paymentMethod, BigDecimal amountPaid, String upiReference, String prescribingDoctor) {}
}
