package com.nammamedmate.customer.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nammamedmate.customer.application.PaymentMethodService;
import com.nammamedmate.customer.application.PaymentMethodService.CardCommand;
import com.nammamedmate.customer.application.PaymentMethodService.UpiCommand;
import com.nammamedmate.kernel.api.ApiResponse;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CustomerPaymentMethodControllerTest {

  @Mock private PaymentMethodService service;

  private CustomerPaymentMethodController controller;
  private final MedmatePrincipal customer =
      new MedmatePrincipal(UUID.randomUUID(), AuthRole.CUSTOMER, null, TokenScope.FULL, "j");

  @BeforeEach
  void setUp() {
    controller = new CustomerPaymentMethodController(service);
  }

  @Test
  void list_delegates() {
    when(service.list(customer)).thenReturn(Map.of("upi", java.util.List.of()));

    ApiResponse<Map<String, Object>> response = controller.list(customer);

    assertThat(response.data()).containsKey("upi");
    verify(service).list(customer);
  }

  @Test
  void saveUpi_mapsBodyAndIdempotencyHeader() {
    when(service.saveUpi(eq(customer), any(UpiCommand.class), eq("idem-1")))
        .thenReturn(Map.of("type", "UPI"));

    ApiResponse<Map<String, Object>> response =
        controller.saveUpi(
            customer, "idem-1", new CustomerPaymentMethodController.UpiRequest("a@okaxis", "GPay"));

    assertThat(response.data()).containsEntry("type", "UPI");
    ArgumentCaptor<UpiCommand> captor = ArgumentCaptor.forClass(UpiCommand.class);
    verify(service).saveUpi(eq(customer), captor.capture(), eq("idem-1"));
    assertThat(captor.getValue().upiId()).isEqualTo("a@okaxis");
    assertThat(captor.getValue().nickname()).isEqualTo("GPay");
  }

  @Test
  void saveUpi_nullBody() {
    when(service.saveUpi(eq(customer), isNull(), isNull())).thenReturn(Map.of());
    controller.saveUpi(customer, null, null);
    verify(service).saveUpi(customer, null, null);
  }

  @Test
  void saveCard_mapsBody() {
    when(service.saveCard(eq(customer), any(CardCommand.class), isNull()))
        .thenReturn(Map.of("type", "CARD"));

    controller.saveCard(
        customer,
        null,
        new CustomerPaymentMethodController.CardRequest(
            "token_x", "4242", "VISA", "CREDIT", "Nick"));

    ArgumentCaptor<CardCommand> captor = ArgumentCaptor.forClass(CardCommand.class);
    verify(service).saveCard(eq(customer), captor.capture(), isNull());
    assertThat(captor.getValue().razorpayTokenId()).isEqualTo("token_x");
    assertThat(captor.getValue().cardLast4()).isEqualTo("4242");
  }

  @Test
  void saveCard_nullBody() {
    when(service.saveCard(eq(customer), isNull(), isNull())).thenReturn(Map.of());
    controller.saveCard(customer, null, null);
    verify(service).saveCard(customer, null, null);
  }

  @Test
  void delete_delegates() {
    UUID id = UUID.randomUUID();
    when(service.delete(customer, id)).thenReturn(Map.of("message", "ok"));
    assertThat(controller.delete(customer, id).data()).containsEntry("message", "ok");
  }

  @Test
  void setDefault_delegates() {
    UUID id = UUID.randomUUID();
    when(service.setDefault(customer, id)).thenReturn(Map.of("is_default", true));
    assertThat(controller.setDefault(customer, id).data()).containsEntry("is_default", true);
  }
}
