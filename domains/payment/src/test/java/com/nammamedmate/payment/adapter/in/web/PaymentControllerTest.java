package com.nammamedmate.payment.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.kernel.api.ApiResponse;
import com.nammamedmate.messaging.WebhookInbox;
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
import org.springframework.beans.factory.ObjectProvider;
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
    when(payments.initiate(eq(principal), eq(orderId), eq(100L), eq("INR"), eq("UPI"), isNull()))
        .thenReturn(Map.of("payment_id", "p1"));
    ResponseEntity<ApiResponse<Map<String, Object>>> created =
        controller.initiate(
            principal, null, new PaymentController.InitiateRequest(orderId, 100L, "INR", "UPI"));
    assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(created.getBody().data().get("payment_id")).isEqualTo("p1");

    controller.initiate(principal, "idem-1", null);
    verify(payments).initiate(eq(principal), isNull(), isNull(), isNull(), isNull(), eq("idem-1"));

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

  @Test
  void webhookInboxDedupsAndClaims() {
    WebhookInbox box = mock(WebhookInbox.class);
    ObjectProvider<WebhookInbox> provider = mock(ObjectProvider.class);
    when(provider.getIfAvailable()).thenReturn(box);
    when(box.alreadyReceived("razorpay", "evt_1")).thenReturn(true);
    PaymentWebhookController gated =
        new PaymentWebhookController(payments, provider, new ObjectMapper());
    MockHttpServletRequest request = new MockHttpServletRequest();
    byte[] body = "{\"id\":\"evt_1\",\"event\":\"payment.captured\"}".getBytes();
    request.setAttribute(WebhookRawBodyFilter.CACHED_BODY_ATTR, body);
    assertThat(gated.razorpay("sig", request).data().get("event")).isEqualTo("duplicate");
    verify(payments, never()).handleWebhook(anyString(), any());

    when(box.alreadyReceived("razorpay", "evt_1")).thenReturn(false);
    when(payments.handleWebhook(anyString(), any())).thenReturn(Map.of("processed", true));
    assertThat(gated.razorpay("sig", request).data().get("processed")).isEqualTo(true);
    verify(box).claim(eq("razorpay"), eq("evt_1"), anyString());
    gated.razorpay("sig", new MockHttpServletRequest());
  }
}
