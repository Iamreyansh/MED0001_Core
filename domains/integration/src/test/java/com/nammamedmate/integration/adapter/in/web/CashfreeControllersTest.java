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

import com.nammamedmate.integration.application.CashfreeIntegrationService;
import com.nammamedmate.integration.application.InternalServiceAuth;
import com.nammamedmate.kernel.api.ApiResponse;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.webhook.WebhookRawBodyFilter;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class CashfreeControllersTest {

  private CashfreeIntegrationService service;
  private InternalServiceAuth auth;
  private CashfreeIntegrationController cashfree;
  private CashfreePayoutController cashfreeX;

  @BeforeEach
  void setUp() {
    service = mock(CashfreeIntegrationService.class);
    auth = new InternalServiceAuth("tok");
    cashfree = new CashfreeIntegrationController(service, auth);
    cashfreeX = new CashfreePayoutController(service, auth);
  }

  @Test
  void createOrderRequiresToken() {
    assertThatThrownBy(() -> cashfree.createOrder(null, null)).isInstanceOf(AppException.class);
    when(service.createOrder(anyLong(), any(), any(), any()))
        .thenReturn(Map.of("status", "created"));
    ApiResponse<Map<String, Object>> res =
        cashfree.createOrder(
            "tok", new CashfreeIntegrationController.CreateOrderRequest(500, "INR", "r", Map.of()));
    assertThat(res.success()).isTrue();
    assertThat(res.data().get("status")).isEqualTo("created");
  }

  @Test
  void webhookUsesRawBody() {
    MockHttpServletRequest request = new MockHttpServletRequest();
    byte[] body = "{\"event\":\"x\"}".getBytes();
    request.setAttribute(WebhookRawBodyFilter.CACHED_BODY_ATTR, body);
    ApiResponse<Void> res = cashfree.webhook("sig", null, request);
    assertThat(res.success()).isTrue();
    verify(service).handleWebhook(eq("sig"), eq(null), any());
  }

  @Test
  void verifyUpiAndFundAccount() {
    when(service.verifyUpi("a@okicici")).thenReturn(Map.of("valid", true));
    when(service.verifyUpi(null)).thenReturn(Map.of("valid", false));
    assertThat(
            cashfree
                .verifyUpi("tok", new CashfreeIntegrationController.VerifyUpiRequest("a@okicici"))
                .data()
                .get("valid"))
        .isEqualTo(true);
    cashfree.verifyUpi("tok", null);
    UUID entity = UUID.randomUUID();
    when(service.createBeneficiary(any(), any(), any(), any(), any(), any()))
        .thenReturn(Map.of("account_last4", "6789"));
    assertThat(
            cashfree
                .fundAccount(
                    "tok",
                    new CashfreeIntegrationController.FundAccountRequest(
                        "PHARMACY", entity, "HDFC", "50100123456789", "HDFC0001234", "Name"))
                .data()
                .get("account_last4"))
        .isEqualTo("6789");
    cashfree.fundAccount("tok", null);
    cashfree.createOrder("tok", null);
  }

  @Test
  void payoutEndpoint() {
    when(service.initiatePayout(anyString(), anyLong(), any(), any(), any(), anyMap()))
        .thenReturn(Map.of("mode", "IMPS"));
    assertThat(
            cashfreeX
                .payout(
                    "tok",
                    new CashfreePayoutController.PayoutRequest(
                        "fa", 1000, null, "payout", "ref", Map.of()))
                .data()
                .get("mode"))
        .isEqualTo("IMPS");
    cashfreeX.payout("tok", null);
    verify(service).initiatePayout(isNull(), eq(0L), isNull(), isNull(), isNull(), isNull());
  }
}
