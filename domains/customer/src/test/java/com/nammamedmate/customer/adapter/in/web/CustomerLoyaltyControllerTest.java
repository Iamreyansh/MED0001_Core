package com.nammamedmate.customer.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;

import com.nammamedmate.customer.application.LoyaltyService;
import com.nammamedmate.customer.application.LoyaltyService.TxPage;
import com.nammamedmate.kernel.api.ApiResponse;
import com.nammamedmate.kernel.api.PaginationMeta;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CustomerLoyaltyControllerTest {

  @Mock private LoyaltyService service;
  private CustomerLoyaltyController controller;
  private final MedmatePrincipal customer =
      new MedmatePrincipal(UUID.randomUUID(), AuthRole.CUSTOMER, null, TokenScope.FULL, "j");

  @BeforeEach
  void setUp() {
    controller = new CustomerLoyaltyController(service);
  }

  @Test
  void get_delegates() {
    when(service.getMyStatus(customer)).thenReturn(Map.of("tier", "GOLD"));
    ApiResponse<Map<String, Object>> response = controller.get(customer);
    assertThat(response.data()).containsEntry("tier", "GOLD");
  }

  @Test
  void transactions_delegates() {
    TxPage page = new TxPage(List.of(Map.of("type", "EARN")), PaginationMeta.of(1, 20, 1));
    when(service.listMyTransactions(eq(customer), isNull(), isNull(), isNull(), isNull()))
        .thenReturn(page);
    ApiResponse<List<Map<String, Object>>> response =
        controller.transactions(customer, null, null, null, null);
    assertThat(response.data()).hasSize(1);
    assertThat(response.meta()).isEqualTo(page.meta());
  }

  @Test
  void redeem_delegatesAndValidates() {
    UUID cartId = Ids.newId();
    when(service.redeem(customer, 20, cartId)).thenReturn(Map.of("points_redeemed", 20));
    ApiResponse<Map<String, Object>> ok =
        controller.redeem(
            customer, new CustomerLoyaltyController.RedeemRequest(20, cartId.toString()));
    assertThat(ok.data()).containsEntry("points_redeemed", 20);

    assertThatThrownBy(() -> controller.redeem(customer, null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(
            () ->
                controller.redeem(
                    customer, new CustomerLoyaltyController.RedeemRequest(0, cartId.toString())))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(
            () ->
                controller.redeem(
                    customer, new CustomerLoyaltyController.RedeemRequest(10, "not-uuid")))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(
            () ->
                controller.redeem(customer, new CustomerLoyaltyController.RedeemRequest(10, "  ")))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(
            () ->
                controller.redeem(customer, new CustomerLoyaltyController.RedeemRequest(10, null)))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
  }
}
