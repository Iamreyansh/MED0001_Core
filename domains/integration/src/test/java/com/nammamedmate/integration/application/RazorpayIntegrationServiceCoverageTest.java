package com.nammamedmate.integration.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.integration.adapter.out.client.RazorpayHmac;
import com.nammamedmate.integration.adapter.out.client.StubRazorpayClient;
import com.nammamedmate.integration.application.port.out.RazorpayClientPort;
import com.nammamedmate.integration.application.port.out.RazorpayXClientPort;
import com.nammamedmate.integration.domain.PaymentStatuses;
import com.nammamedmate.integration.domain.PayoutModes;
import com.nammamedmate.integration.domain.PayoutStatuses;
import com.nammamedmate.integration.domain.RazorpayPaymentRecord;
import com.nammamedmate.integration.domain.RazorpayXFundAccount;
import com.nammamedmate.integration.domain.RazorpayXPayoutRecord;
import com.nammamedmate.integration.support.InMemoryStores;
import com.nammamedmate.kernel.error.AppException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RazorpayIntegrationServiceCoverageTest {

  private static final String WHSEC = StubRazorpayClient.DEFAULT_WEBHOOK_SECRET;
  private static final Instant NOW = Instant.parse("2026-07-24T12:00:00Z");

  private InMemoryStores.Payments payments;
  private InMemoryStores.FundAccounts fundAccounts;
  private InMemoryStores.Payouts payouts;
  private RazorpayClientPort razorpay;
  private RazorpayXClientPort razorpayX;
  private RazorpayIntegrationService service;

  @BeforeEach
  void setUp() {
    payments = new InMemoryStores.Payments();
    fundAccounts = new InMemoryStores.FundAccounts();
    payouts = new InMemoryStores.Payouts();
    razorpay = mock(RazorpayClientPort.class);
    razorpayX = mock(RazorpayXClientPort.class);
    doAnswer(
            inv -> {
              String sig = inv.getArgument(0);
              byte[] body = inv.getArgument(1);
              if (sig == null || body == null) {
                return false;
              }
              String expected =
                  RazorpayHmac.hmacHex(WHSEC, new String(body, StandardCharsets.UTF_8));
              return expected.equals(sig);
            })
        .when(razorpay)
        .verifyWebhookSignature(any(), any());
    doReturn("TEST").when(razorpay).mode();
    service =
        new RazorpayIntegrationService(
            razorpay,
            razorpayX,
            payments,
            fundAccounts,
            payouts,
            (t, a, i, p) -> {},
            new ObjectMapper(),
            Clock.fixed(NOW, ZoneOffset.UTC));
  }

  @Test
  void createOrderDefaultsAndRuntimeFailureAndBlankReceipt() {
    doReturn(new RazorpayClientPort.CreateOrderResult("order_1", 100, "INR", "", "created"))
        .when(razorpay)
        .createOrder(anyLong(), anyString(), anyString(), anyMap());
    Map<String, Object> data = service.createOrder(100, null, null, null);
    assertThat(data.get("currency")).isEqualTo("INR");
    assertThat(data.get("receipt")).isEqualTo("");

    doThrow(new AppException("RAZORPAY_UNAVAILABLE", "x", 503))
        .when(razorpay)
        .createOrder(anyLong(), anyString(), anyString(), anyMap());
    assertCode(() -> service.createOrder(100, "INR", "r", Map.of()), "RAZORPAY_UNAVAILABLE");

    doThrow(new RuntimeException("boom"))
        .when(razorpay)
        .createOrder(anyLong(), anyString(), anyString(), anyMap());
    assertCode(() -> service.createOrder(100, "INR", "r", Map.of()), "RAZORPAY_UNAVAILABLE");

    doReturn(new RazorpayClientPort.CreateOrderResult("order_2", 100, "INR", "kept", "created"))
        .when(razorpay)
        .createOrder(anyLong(), anyString(), anyString(), anyMap());
    assertThat(
            service
                .createOrder(100, " ", "r", Map.of("platform_order_id", "not-uuid"))
                .get("receipt"))
        .isEqualTo("kept");
  }

  @Test
  void webhookNullBodyAndInvalidJson() {
    doReturn(true).when(razorpay).verifyWebhookSignature(any(), any());
    service.handleWebhook("x", null);
    byte[] bad = "{".getBytes(StandardCharsets.UTF_8);
    assertCode(() -> service.handleWebhook("x", bad), "VALIDATION_ERROR");
  }

  @Test
  void payoutInactiveBlankModeUpiNotesAndXFailures() {
    RazorpayXFundAccount inactive =
        new RazorpayXFundAccount(
            UUID.randomUUID(),
            "PHARMACY",
            UUID.randomUUID(),
            "c",
            "fa_inactive",
            "b",
            "1234",
            "HDFC0001234",
            "n",
            false,
            NOW);
    fundAccounts.insert(inactive);
    assertCode(
        () -> service.initiatePayout("", 1, null, null, null, null), "FUND_ACCOUNT_NOT_FOUND");
    assertCode(
        () -> service.initiatePayout("fa_inactive", 1, null, null, null, null),
        "FUND_ACCOUNT_NOT_FOUND");

    RazorpayXFundAccount fa =
        new RazorpayXFundAccount(
            UUID.randomUUID(),
            "PHARMACY",
            UUID.randomUUID(),
            "c",
            "fa_ok",
            "b",
            "1234",
            "HDFC0001234",
            "n",
            true,
            NOW);
    fundAccounts.insert(fa);
    doReturn(new RazorpayXClientPort.PayoutResult("pout_1", ""))
        .when(razorpayX)
        .createPayout(any());
    Map<String, Object> out =
        service.initiatePayout("fa_ok", 100, "UPI", " ", null, Map.of("entity_type", "RIDER"));
    assertThat(out.get("mode")).isEqualTo("UPI");
    assertThat(out.get("status")).isEqualTo(PayoutStatuses.PROCESSING);

    doReturn(new RazorpayXClientPort.PayoutResult("pout_2", "processing"))
        .when(razorpayX)
        .createPayout(any());
    assertThat(service.initiatePayout("fa_ok", 100, "ZZZ", null, "R2", null).get("mode"))
        .isEqualTo(PayoutModes.IMPS);

    doThrow(new AppException("INSUFFICIENT_BALANCE", "x", 422)).when(razorpayX).createPayout(any());
    assertCode(
        () -> service.initiatePayout("fa_ok", 100, null, null, "R3", Map.of()),
        "INSUFFICIENT_BALANCE");

    doThrow(new RuntimeException("down")).when(razorpayX).createPayout(any());
    assertCode(
        () -> service.initiatePayout("fa_ok", 100, null, null, "R4", Map.of()),
        "RAZORPAYX_UNAVAILABLE");
  }

  @Test
  void fundAccountNullEntityAndXFailures() {
    assertCode(
        () ->
            service.createFundAccount("PHARMACY", null, "b", "50100123456789", "HDFC0001234", "n"),
        "VALIDATION_ERROR");
    doThrow(new AppException("RAZORPAYX_UNAVAILABLE", "x", 503))
        .when(razorpayX)
        .createFundAccount(any());
    assertCode(
        () ->
            service.createFundAccount(
                "PHARMACY", UUID.randomUUID(), null, "50100123456789", "HDFC0001234", null),
        "RAZORPAYX_UNAVAILABLE");
    doThrow(new RuntimeException("x")).when(razorpayX).createFundAccount(any());
    assertCode(
        () ->
            service.createFundAccount(
                "PHARMACY", UUID.randomUUID(), "b", "50100123456789", "HDFC0001234", "n"),
        "RAZORPAYX_UNAVAILABLE");
  }

  @Test
  void authorizedEdgeCasesAndCaptureFailures() {
    webhook(
        "{\"event\":\"payment.authorized\",\"payload\":{\"payment\":{\"entity\":{\"order_id\":\"o\"}}}}");
    payments.insert(
        new RazorpayPaymentRecord(
            UUID.randomUUID(),
            UUID.randomUUID(),
            "ord_c",
            "pay_c",
            10,
            "INR",
            "upi",
            PaymentStatuses.CAPTURED,
            NOW,
            NOW));
    webhook(
        "{\"event\":\"payment.authorized\",\"payload\":{\"payment\":{\"entity\":{\"id\":\"pay_c\",\"order_id\":\"ord_c\",\"amount\":10}}}}");

    payments.insert(
        new RazorpayPaymentRecord(
            UUID.randomUUID(),
            UUID.randomUUID(),
            "ord_a",
            null,
            50,
            "INR",
            "upi",
            PaymentStatuses.CREATED,
            NOW,
            null));
    doThrow(new AppException("RAZORPAY_UNAVAILABLE", "x", 503))
        .when(razorpay)
        .capturePayment(anyString(), anyLong());
    assertCode(
        () ->
            webhook(
                "{\"event\":\"payment.authorized\",\"payload\":{\"payment\":{\"entity\":{\"id\":\"pay_a\",\"order_id\":\"ord_a\",\"amount\":50}}}}"),
        "RAZORPAY_UNAVAILABLE");

    doThrow(new RuntimeException("x")).when(razorpay).capturePayment(anyString(), anyLong());
    payments.insert(
        new RazorpayPaymentRecord(
            UUID.randomUUID(),
            UUID.randomUUID(),
            "ord_b",
            null,
            50,
            "INR",
            null,
            PaymentStatuses.CREATED,
            NOW,
            null));
    assertCode(
        () ->
            webhook(
                "{\"event\":\"payment.authorized\",\"payload\":{\"payment\":{\"entity\":{\"id\":\"pay_b\",\"order_id\":\"ord_b\",\"amount\":50,\"method\":\"card\"}}}}"),
        "RAZORPAY_UNAVAILABLE");

    doReturn(new RazorpayClientPort.CaptureResult("pay_n", null))
        .when(razorpay)
        .capturePayment(anyString(), anyLong());
    webhook(
        "{\"event\":\"payment.authorized\",\"payload\":{\"payment\":{\"entity\":{\"id\":\"pay_n\",\"order_id\":\"ord_n\",\"amount\":9}}}}");
  }

  @Test
  void capturedUpdatePathAndFailedLookups() {
    UUID id = UUID.randomUUID();
    payments.insert(
        new RazorpayPaymentRecord(
            id,
            UUID.randomUUID(),
            "ord_u",
            null,
            11,
            "INR",
            "upi",
            PaymentStatuses.AUTHORIZED,
            NOW,
            null));
    webhook(
        "{\"event\":\"payment.captured\",\"payload\":{\"payment\":{\"entity\":{\"id\":\"pay_u\",\"order_id\":\"ord_u\",\"amount\":11}}}}");
    assertThat(payments.findById(id).get().status()).isEqualTo(PaymentStatuses.CAPTURED);

    webhook(
        "{\"event\":\"payment.captured\",\"payload\":{\"payment\":{\"entity\":{\"amount\":1}}}}");
    webhook(
        "{\"event\":\"payment.captured\",\"payload\":{\"payment\":{\"entity\":{\"id\":\"pay_only\",\"amount\":1}}}}");
    webhook("{\"event\":\"payment.failed\",\"payload\":{\"payment\":{\"entity\":{}}}}");
    payments.insert(
        new RazorpayPaymentRecord(
            UUID.randomUUID(),
            UUID.randomUUID(),
            "ord_fail",
            null,
            1,
            "INR",
            "upi",
            PaymentStatuses.CREATED,
            NOW,
            null));
    webhook(
        "{\"event\":\"payment.failed\",\"payload\":{\"payment\":{\"entity\":{\"order_id\":\"ord_fail\"}}}}");
    webhook("{\"event\":\"refund.created\",\"payload\":{\"refund\":{\"entity\":{}}}}");
    webhook("{\"event\":\"payout.processed\",\"payload\":{\"payout\":{\"entity\":{}}}}");
    webhook("{\"event\":\"payout.failed\",\"payload\":{\"payout\":{\"entity\":{}}}}");

    UUID pout = UUID.randomUUID();
    payouts.insert(
        new RazorpayXPayoutRecord(
            pout,
            "PHARMACY",
            UUID.randomUUID(),
            "fa",
            "pout_dup",
            "r",
            1,
            "IMPS",
            PayoutStatuses.PROCESSED,
            0,
            NOW,
            NOW,
            null));
    webhook(
        "{\"event\":\"payout.processed\",\"payload\":{\"payout\":{\"entity\":{\"id\":\"pout_dup\"}}}}");

    payouts.insert(
        new RazorpayXPayoutRecord(
            UUID.randomUUID(),
            "PHARMACY",
            UUID.randomUUID(),
            "fa",
            "pout_f2",
            "r2",
            1,
            "IMPS",
            PayoutStatuses.PROCESSING,
            1,
            NOW,
            null,
            null));
    webhook(
        "{\"event\":\"payout.failed\",\"payload\":{\"payout\":{\"entity\":{\"id\":\"pout_f2\"}}}}");
  }

  @Test
  void retryEligibleEmpty() {
    assertThat(service.retryFailedPayouts()).isZero();
  }

  private void webhook(String json) {
    byte[] body = json.getBytes(StandardCharsets.UTF_8);
    service.handleWebhook(RazorpayHmac.hmacHex(WHSEC, json), body);
  }

  private static void assertCode(Runnable action, String code) {
    try {
      action.run();
      throw new AssertionError("expected AppException " + code);
    } catch (AppException ex) {
      assertThat(ex.code()).isEqualTo(code);
    }
  }
}
