package com.nammamedmate.payment.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nammamedmate.kernel.api.ApiResponse;
import com.nammamedmate.kernel.webhook.WebhookRawBodyFilter;
import com.nammamedmate.payment.application.PaymentService;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

@ExtendWith(MockitoExtension.class)
class PaymentControllerTest {

  @Mock private PaymentService payments;
  private PaymentController controller;
  private PaymentWebhookController webhook;
  private final MedmatePrincipal principal =
      new MedmatePrincipal(UUID.randomUUID(), AuthRole.CUSTOMER, null, TokenScope.FULL, "j");

  @BeforeEach
  void setUp() {
    controller = new PaymentController(payments);
    webhook = new PaymentWebhookController(payments);
  }

  @Test
  void initiateVerifyGet() {
    UUID orderId = UUID.randomUUID();
    when(payments.initiate(eq(principal), eq(orderId), eq(100L), eq("INR"), eq("UPI")))
        .thenReturn(Map.of("payment_id", "p1"));
    ResponseEntity<ApiResponse<Map<String, Object>>> created =
        controller.initiate(
            principal, new PaymentController.InitiateRequest(orderId, 100L, "INR", "UPI"));
    assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(created.getBody().data().get("payment_id")).isEqualTo("p1");

    controller.initiate(principal, null);
    verify(payments).initiate(eq(principal), isNull(), isNull(), isNull(), isNull());

    when(payments.verify(eq(principal), eq("pay"), eq("ord"), eq("sig")))
        .thenReturn(Map.of("payment_status", "CAPTURED"));
    assertThat(
            controller
                .verify(principal, new PaymentController.VerifyRequest("pay", "ord", "sig"))
                .data()
                .get("payment_status"))
        .isEqualTo("CAPTURED");
    controller.verify(principal, null);
    verify(payments).verify(eq(principal), isNull(), isNull(), isNull());

    UUID paymentId = UUID.randomUUID();
    when(payments.getPayment(principal, paymentId)).thenReturn(Map.of("status", "PENDING"));
    assertThat(controller.get(principal, paymentId).data().get("status")).isEqualTo("PENDING");
  }

  @Test
  void webhookUsesRawBody() {
    MockHttpServletRequest request = new MockHttpServletRequest();
    byte[] body = "{\"event\":\"payment.captured\"}".getBytes();
    request.setAttribute(WebhookRawBodyFilter.CACHED_BODY_ATTR, body);
    when(payments.handleWebhook(anyString(), any())).thenReturn(Map.of("processed", true));
    ApiResponse<Map<String, Object>> res = webhook.razorpay("sig", request);
    assertThat(res.data().get("processed")).isEqualTo(true);
    verify(payments).handleWebhook(eq("sig"), eq(body));
  }
}
