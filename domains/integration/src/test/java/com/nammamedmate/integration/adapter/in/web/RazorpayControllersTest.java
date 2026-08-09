package com.nammamedmate.integration.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nammamedmate.integration.application.InternalServiceAuth;
import com.nammamedmate.integration.application.RazorpayIntegrationService;
import com.nammamedmate.kernel.api.ApiResponse;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.webhook.WebhookRawBodyFilter;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class RazorpayControllersTest {

  private RazorpayIntegrationService service;
  private InternalServiceAuth auth;
  private RazorpayIntegrationController razorpay;
  private RazorpayXPayoutController razorpayX;

  @BeforeEach
  void setUp() {
    service = mock(RazorpayIntegrationService.class);
    auth = new InternalServiceAuth("tok");
    razorpay = new RazorpayIntegrationController(service, auth);
    razorpayX = new RazorpayXPayoutController(service, auth);
  }

  @Test
  void createOrderRequiresToken() {
    assertThatThrownBy(() -> razorpay.createOrder(null, null)).isInstanceOf(AppException.class);
    when(service.createOrder(anyLong(), any(), any(), any()))
        .thenReturn(Map.of("status", "created"));
    ApiResponse<Map<String, Object>> res =
        razorpay.createOrder(
            "tok", new RazorpayIntegrationController.CreateOrderRequest(500, "INR", "r", Map.of()));
    assertThat(res.success()).isTrue();
    assertThat(res.data().get("status")).isEqualTo("created");
  }

  @Test
  void webhookUsesRawBody() {
    MockHttpServletRequest request = new MockHttpServletRequest();
    byte[] body = "{\"event\":\"x\"}".getBytes();
    request.setAttribute(WebhookRawBodyFilter.CACHED_BODY_ATTR, body);
    ApiResponse<Void> res = razorpay.webhook("sig", request);
    assertThat(res.success()).isTrue();
    verify(service).handleWebhook(eq("sig"), any());
  }

  @Test
  void verifyUpiAndFundAccount() {
    when(service.verifyUpi("a@okicici")).thenReturn(Map.of("valid", true));
    when(service.verifyUpi(null)).thenReturn(Map.of("valid", false));
    assertThat(
            razorpay
                .verifyUpi("tok", new RazorpayIntegrationController.VerifyUpiRequest("a@okicici"))
                .data()
                .get("valid"))
        .isEqualTo(true);
    razorpay.verifyUpi("tok", null);
    UUID entity = UUID.randomUUID();
    when(service.createFundAccount(any(), any(), any(), any(), any(), any()))
        .thenReturn(Map.of("account_last4", "6789"));
    assertThat(
            razorpay
                .fundAccount(
                    "tok",
                    new RazorpayIntegrationController.FundAccountRequest(
                        "PHARMACY", entity, "HDFC", "50100123456789", "HDFC0001234", "Name"))
                .data()
                .get("account_last4"))
        .isEqualTo("6789");
    razorpay.fundAccount("tok", null);
    razorpay.createOrder("tok", null);
  }

  @Test
  void payoutEndpoint() {
    when(service.initiatePayout(anyString(), anyLong(), any(), any(), any(), anyMap()))
        .thenReturn(Map.of("mode", "IMPS"));
    assertThat(
            razorpayX
                .payout(
                    "tok",
                    new RazorpayXPayoutController.PayoutRequest(
                        "fa", 1000, null, "payout", "ref", Map.of()))
                .data()
                .get("mode"))
        .isEqualTo("IMPS");
    razorpayX.payout("tok", null);
    verify(service).initiatePayout(isNull(), eq(0L), isNull(), isNull(), isNull(), isNull());
  }
}
