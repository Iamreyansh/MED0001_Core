package com.nammamedmate.pos.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nammamedmate.kernel.api.ApiResponse;
import com.nammamedmate.pos.application.PosCartService;
import com.nammamedmate.pos.application.PosCheckoutService;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

@ExtendWith(MockitoExtension.class)
class PharmacyPosControllerTest {

  @Mock PosCartService cartService;
  @Mock PosCheckoutService checkoutService;
  PharmacyPosController controller;
  MedmatePrincipal principal =
      new MedmatePrincipal(
          UUID.randomUUID(), AuthRole.PHARMACY_OWNER, UUID.randomUUID(), TokenScope.FULL, "j");

  @BeforeEach
  void setUp() {
    controller = new PharmacyPosController(cartService, checkoutService);
  }

  @Test
  void allEndpointsDelegate() {
    UUID cartId = UUID.randomUUID();
    UUID itemId = UUID.randomUUID();
    when(cartService.createCart(any(), any())).thenReturn(Map.of("cart_id", cartId.toString()));
    when(cartService.getCart(any(), eq(cartId))).thenReturn(Map.of("status", "ACTIVE"));
    when(cartService.addItem(any(), eq(cartId), any(), any(), any(), any()))
        .thenReturn(Map.of("item_id", itemId.toString()));
    when(cartService.updateItem(any(), eq(cartId), eq(itemId), any(), any(), any()))
        .thenReturn(Map.of("quantity", 2));
    when(cartService.removeItem(any(), eq(cartId), eq(itemId)))
        .thenReturn(Map.of("item_id", itemId.toString()));
    when(cartService.clearCart(any(), eq(cartId))).thenReturn(Map.of("items_removed", 1));
    when(cartService.search(any(), eq(cartId), any(), any()))
        .thenReturn(Map.of("results", java.util.List.of()));
    when(cartService.attachCustomer(any(), eq(cartId), any(), any()))
        .thenReturn(Map.of("customer_id", UUID.randomUUID().toString()));
    when(cartService.applyDiscount(any(), eq(cartId), any(), any()))
        .thenReturn(Map.of("discount_type", "FLAT_RS"));
    when(checkoutService.checkout(any(), eq(cartId), any(), any(), any(), any(), any()))
        .thenReturn(Map.of("invoice_id", UUID.randomUUID().toString()));

    ResponseEntity<ApiResponse<Map<String, Object>>> created =
        controller.createCart(principal, new PharmacyPosController.CreateCartRequest(null));
    assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);

    assertThat(controller.getCart(principal, cartId).data()).containsEntry("status", "ACTIVE");

    assertThat(
            controller
                .addItem(
                    principal,
                    cartId,
                    new PharmacyPosController.AddItemRequest(UUID.randomUUID(), null, 1, false))
                .getStatusCode())
        .isEqualTo(HttpStatus.CREATED);

    controller.updateItem(
        principal, cartId, itemId, new PharmacyPosController.UpdateItemRequest(3, null, null));
    controller.removeItem(principal, cartId, itemId);
    controller.clearCart(principal, cartId);
    controller.search(principal, cartId, new PharmacyPosController.SearchRequest("para", "TEXT"));
    controller.attachCustomer(
        principal, cartId, new PharmacyPosController.CustomerRequest("+9198", "A"));
    controller.applyDiscount(
        principal, cartId, new PharmacyPosController.DiscountRequest("FLAT_RS", BigDecimal.TEN));
    assertThat(
            controller
                .checkout(
                    principal,
                    cartId,
                    null,
                    new PharmacyPosController.CheckoutRequest(
                        "CASH", BigDecimal.valueOf(100), null, null))
                .getStatusCode())
        .isEqualTo(HttpStatus.CREATED);

    // null bodies
    controller.createCart(principal, null);
    controller.addItem(principal, cartId, null);
    controller.updateItem(principal, cartId, itemId, null);
    controller.search(principal, cartId, null);
    controller.attachCustomer(principal, cartId, null);
    controller.applyDiscount(principal, cartId, null);
    controller.checkout(principal, cartId, null, null);
    verify(checkoutService)
        .checkout(eq(principal), eq(cartId), isNull(), isNull(), isNull(), isNull(), isNull());
  }
}
