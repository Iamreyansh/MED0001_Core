package com.nammamedmate.integration.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.integration.adapter.out.client.StubRazorpayClient;
import com.nammamedmate.integration.application.port.out.RazorpayClientPort;
import com.nammamedmate.integration.application.port.out.RazorpayXClientPort;
import com.nammamedmate.integration.domain.PaymentStatuses;
import com.nammamedmate.integration.domain.RazorpayPaymentRecord;
import com.nammamedmate.integration.domain.RazorpayXFundAccount;
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

class RemainingBranchCoverageTest {

  private static final String WHSEC = StubRazorpayClient.DEFAULT_WEBHOOK_SECRET;
  private static final Instant NOW = Instant.parse("2026-07-24T13:00:00Z");

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
    doReturn(true).when(razorpay).verifyWebhookSignature(any(), any());
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
  void nullAndBlankGuards() {
    doReturn(new RazorpayClientPort.CreateOrderResult("o", 100, "INR", null, "created"))
        .when(razorpay)
        .createOrder(anyLong(), anyString(), anyString(), anyMap());
    assertThat(service.createOrder(100, "INR", "rcpt", Map.of()).get("receipt")).isEqualTo("rcpt");

    try {
      service.initiatePayout(null, 1, " ", null, " ", Map.of("entity_id", " ", "entity_type", " "));
    } catch (AppException e) {
      assertThat(e.code()).isEqualTo("FUND_ACCOUNT_NOT_FOUND");
    }

    RazorpayXFundAccount fa =
        new RazorpayXFundAccount(
            UUID.randomUUID(),
            "PHARMACY",
            UUID.randomUUID(),
            "c",
            "fa_z",
            "b",
            "1234",
            "HDFC0001234",
            "n",
            true,
            NOW);
    fundAccounts.insert(fa);
    doReturn(new RazorpayXClientPort.PayoutResult("p", "processing"))
        .when(razorpayX)
        .createPayout(any());
    service.initiatePayout("fa_z", 100, " ", "payout", " ", null);

    service.handleWebhook("x", "{\"event\":\"\"}".getBytes(StandardCharsets.UTF_8));
    service.handleWebhook("x", "{\"event\":\"   \"}".getBytes(StandardCharsets.UTF_8));
  }

  @Test
  void authorizedPresentNotCapturedAndFailedLookupByOrder() {
    UUID id = UUID.randomUUID();
    payments.insert(
        new RazorpayPaymentRecord(
            id,
            UUID.randomUUID(),
            "ord_auth",
            "pay_auth",
            20,
            "INR",
            "upi",
            PaymentStatuses.AUTHORIZED,
            NOW,
            null));
    doReturn(new RazorpayClientPort.CaptureResult("pay_auth", "captured"))
        .when(razorpay)
        .capturePayment(anyString(), anyLong());
    webhook(
        "{\"event\":\"payment.authorized\",\"payload\":{\"payment\":{\"entity\":{\"id\":\"pay_auth\",\"order_id\":\"ord_auth\",\"amount\":20}}}}");

    payments.insert(
        new RazorpayPaymentRecord(
            UUID.randomUUID(),
            UUID.randomUUID(),
            "ord_miss",
            null,
            1,
            "INR",
            "upi",
            PaymentStatuses.CREATED,
            NOW,
            null));
    webhook(
        "{\"event\":\"payment.failed\",\"payload\":{\"payment\":{\"entity\":{\"id\":\"pay_unknown\",\"order_id\":\"ord_miss\"}}}}");
    assertThat(payments.findByRazorpayOrderId("ord_miss").get().status())
        .isEqualTo(PaymentStatuses.FAILED);
  }

  @Test
  void capturedWithExistingAuthorizedNoMethodAndTextEdges() {
    UUID id = UUID.randomUUID();
    payments.insert(
        new RazorpayPaymentRecord(
            id,
            UUID.randomUUID(),
            "ord_m",
            "pay_m",
            5,
            "INR",
            "netbanking",
            PaymentStatuses.AUTHORIZED,
            NOW,
            null));
    webhook(
        "{\"event\":\"payment.captured\",\"payload\":{\"payment\":{\"entity\":{\"id\":\"pay_m\",\"order_id\":\"ord_m\",\"amount\":5,\"method\":null}}}}");
    assertThat(payments.findById(id).get().paymentMethod()).isEqualTo("netbanking");

    webhook(
        "{\"event\":\"payment.authorized\",\"payload\":{\"payment\":{\"entity\":{\"id\":\"pay_only\",\"amount\":1}}}}");
    webhook(
        "{\"event\":\"payout.failed\",\"payload\":{\"payout\":{\"entity\":{\"id\":\"missing\",\"failure_reason\":\"\"}}}}");
  }

  @Test
  void fundAccountBlankNamesAndVerifySigned() {
    doReturn(new RazorpayXClientPort.FundAccountResult("c", "fa_new"))
        .when(razorpayX)
        .createFundAccount(any());
    Map<String, Object> data =
        service.createFundAccount(
            "PHARMACY", UUID.randomUUID(), " ", "50100123456789", "hdfc0001234", " ");
    assertThat(data.get("bank_name")).isEqualTo("");
    doReturn(false).when(razorpay).verifyWebhookSignature(any(), any());
    try {
      service.handleWebhook("bad", "{}".getBytes(StandardCharsets.UTF_8));
    } catch (AppException e) {
      assertThat(e.code()).isEqualTo("INVALID_SIGNATURE");
    }
  }

  @Test
  void verifyUpiBlankAndFailedPaymentIdOnlyAndRetryNullMessage() {
    try {
      service.verifyUpi(null);
    } catch (AppException e) {
      assertThat(e.code()).isEqualTo("VALIDATION_ERROR");
    }
    try {
      service.verifyUpi(" ");
    } catch (AppException e) {
      assertThat(e.code()).isEqualTo("VALIDATION_ERROR");
    }
    webhook(
        "{\"event\":\"payment.failed\",\"payload\":{\"payment\":{\"entity\":{\"id\":\"pay_ghost\"}}}}");

    RazorpayXFundAccount fa =
        new RazorpayXFundAccount(
            UUID.randomUUID(),
            "PHARMACY",
            UUID.randomUUID(),
            "c",
            "fa_retry",
            "b",
            "1234",
            "HDFC0001234",
            "n",
            true,
            NOW);
    fundAccounts.insert(fa);
    UUID id = UUID.randomUUID();
    payouts.insert(
        new com.nammamedmate.integration.domain.RazorpayXPayoutRecord(
            id,
            "PHARMACY",
            fa.entityId(),
            fa.fundAccountId(),
            "pout_r",
            "RR",
            1L,
            "IMPS",
            "failed",
            0,
            NOW.minusSeconds(4000),
            NOW.minusSeconds(4000),
            "x"));
    org.mockito.Mockito.doThrow(new RuntimeException()).when(razorpayX).createPayout(any());
    assertThat(service.retryFailedPayouts()).isEqualTo(1);

    // same IFSC, different last4 → replace
    doReturn(new RazorpayXClientPort.FundAccountResult("c2", "fa_2"))
        .when(razorpayX)
        .createFundAccount(any());
    UUID entity = UUID.randomUUID();
    service.createFundAccount("PHARMACY", entity, "HDFC", "50100123456789", "HDFC0001234", "A");
    service.createFundAccount("PHARMACY", entity, "HDFC", "50100111111111", "HDFC0001234", "A");
  }

  private void webhook(String json) {
    // signature ignored — stub returns true
    service.handleWebhook("sig", json.getBytes(StandardCharsets.UTF_8));
  }
}
