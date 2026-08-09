package com.nammamedmate.integration.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.integration.adapter.out.client.RazorpayHmac;
import com.nammamedmate.integration.adapter.out.client.StubRazorpayClient;
import com.nammamedmate.integration.adapter.out.client.StubRazorpayXClient;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RazorpayIntegrationServiceBranchTest {

  private static final String WHSEC = StubRazorpayClient.DEFAULT_WEBHOOK_SECRET;
  private static final Instant NOW = Instant.parse("2026-07-24T10:00:00Z");

  private InMemoryStores.Payments payments;
  private InMemoryStores.FundAccounts fundAccounts;
  private InMemoryStores.Payouts payouts;
  private List<String> events;
  private RazorpayIntegrationService service;
  private StubRazorpayXClient razorpayX;

  @BeforeEach
  void setUp() {
    payments = new InMemoryStores.Payments();
    fundAccounts = new InMemoryStores.FundAccounts();
    payouts = new InMemoryStores.Payouts();
    events = new ArrayList<>();
    razorpayX = new StubRazorpayXClient();
    service =
        new RazorpayIntegrationService(
            new StubRazorpayClient(WHSEC),
            razorpayX,
            payments,
            fundAccounts,
            payouts,
            (t, a, i, p) -> events.add(t),
            new ObjectMapper(),
            Clock.fixed(NOW, ZoneOffset.UTC));
  }

  @Test
  void createOrderUnavailable() {
    RazorpayIntegrationService failing =
        new RazorpayIntegrationService(
            new StubRazorpayClient(WHSEC, true),
            razorpayX,
            payments,
            fundAccounts,
            payouts,
            (t, a, i, p) -> {},
            new ObjectMapper(),
            Clock.fixed(NOW, ZoneOffset.UTC));
    assertThatThrownBy(() -> failing.createOrder(100, "INR", "r", Map.of()))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("RAZORPAY_UNAVAILABLE");
  }

  @Test
  void fundAccountReuseAndReplace() {
    UUID entity = UUID.randomUUID();
    Map<String, Object> first =
        service.createFundAccount(
            "RIDER", entity, "HDFC", "50100123456789", "HDFC0001234", "Rider");
    Map<String, Object> again =
        service.createFundAccount(
            "RIDER", entity, "HDFC", "50100123456789", "HDFC0001234", "Rider");
    assertThat(again.get("fund_account_id")).isEqualTo(first.get("fund_account_id"));
    Map<String, Object> changed =
        service.createFundAccount(
            "RIDER", entity, "ICICI", "50100999999999", "ICIC0001234", "Rider");
    assertThat(changed.get("fund_account_id")).isNotEqualTo(first.get("fund_account_id"));
    assertThat(changed.get("account_last4")).isEqualTo("9999");
  }

  @Test
  void payoutIdempotentByReferenceAndModeOverride() {
    RazorpayXFundAccount fa = seedFa();
    Map<String, Object> first =
        service.initiatePayout(fa.fundAccountId(), 1000, "NEFT", "payout", "SAME-REF", Map.of());
    Map<String, Object> second =
        service.initiatePayout(fa.fundAccountId(), 1000, "IMPS", "payout", "SAME-REF", Map.of());
    assertThat(second.get("razorpayx_payout_id")).isEqualTo(first.get("razorpayx_payout_id"));
    assertThat(first.get("mode")).isEqualTo("NEFT");
  }

  @Test
  void paymentFailedAndRefundAndPayoutProcessed() {
    UUID payId = UUID.randomUUID();
    payments.insert(
        new RazorpayPaymentRecord(
            payId,
            UUID.randomUUID(),
            "order_f",
            "pay_f",
            100,
            "INR",
            "upi",
            PaymentStatuses.CAPTURED,
            NOW,
            NOW));
    webhook(
        "{\"event\":\"payment.failed\",\"payload\":{\"payment\":{\"entity\":{\"id\":\"pay_f\",\"order_id\":\"order_f\"}}}}");
    assertThat(payments.findById(payId).get().status()).isEqualTo(PaymentStatuses.FAILED);

    payments.update(
        new RazorpayPaymentRecord(
            payId,
            payments.findById(payId).get().platformOrderId(),
            "order_f",
            "pay_f",
            100,
            "INR",
            "upi",
            PaymentStatuses.CAPTURED,
            NOW,
            NOW));
    webhook(
        "{\"event\":\"refund.processed\",\"payload\":{\"refund\":{\"entity\":{\"payment_id\":\"pay_f\"}}}}");
    assertThat(payments.findById(payId).get().status()).isEqualTo(PaymentStatuses.REFUNDED);
    assertThat(events).contains("REFUND_PROCESSED");

    webhook(
        "{\"event\":\"refund.created\",\"payload\":{\"refund\":{\"entity\":{\"payment_id\":\"pay_f\"}}}}");
    assertThat(events).contains("REFUND_CREATED");

    RazorpayXFundAccount fa = seedFa();
    UUID pout = UUID.randomUUID();
    payouts.insert(
        new RazorpayXPayoutRecord(
            pout,
            "PHARMACY",
            fa.entityId(),
            fa.fundAccountId(),
            "pout_ok",
            "R",
            1L,
            PayoutModes.IMPS,
            PayoutStatuses.PROCESSING,
            0,
            NOW,
            null,
            null));
    webhook(
        "{\"event\":\"payout.processed\",\"payload\":{\"payout\":{\"entity\":{\"id\":\"pout_ok\"}}}}");
    assertThat(payouts.findById(pout).get().status()).isEqualTo(PayoutStatuses.PROCESSED);

    webhook("{\"event\":\"unknown.event\"}");
    webhook("{}");
  }

  @Test
  void invalidVpaAndEntityType() {
    assertThatThrownBy(() -> service.verifyUpi("not-a-vpa"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(
            () ->
                service.createFundAccount(
                    "X", UUID.randomUUID(), "b", "50100123456789", "HDFC0001234", "n"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
  }

  @Test
  void authorizedWithoutExistingOrderStillCaptures() {
    webhook(
        "{\"event\":\"payment.authorized\",\"payload\":{\"payment\":{\"entity\":{\"id\":\"pay_x\",\"order_id\":\"order_x\",\"amount\":200,\"method\":\"card\"}}}}");
    assertThat(payments.findByRazorpayPaymentId("pay_x")).isPresent();
    assertThat(payments.findByRazorpayPaymentId("pay_x").get().status())
        .isEqualTo(PaymentStatuses.CAPTURED);
  }

  @Test
  void payoutUnavailable() {
    RazorpayXFundAccount fa = seedFa();
    razorpayX.setFailPayout(true);
    assertThatThrownBy(
            () ->
                service.initiatePayout(
                    fa.fundAccountId(), 1000, null, "payout", "FAIL-REF", Map.of()))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("RAZORPAYX_UNAVAILABLE");
  }

  private RazorpayXFundAccount seedFa() {
    RazorpayXFundAccount fa =
        new RazorpayXFundAccount(
            UUID.randomUUID(),
            "PHARMACY",
            UUID.randomUUID(),
            "c",
            "fa_" + UUID.randomUUID().toString().substring(0, 8),
            "HDFC",
            "6789",
            "HDFC0001234",
            "N",
            true,
            NOW);
    fundAccounts.insert(fa);
    return fa;
  }

  private void webhook(String json) {
    byte[] body = json.getBytes(StandardCharsets.UTF_8);
    service.handleWebhook(RazorpayHmac.hmacHex(WHSEC, json), body);
  }
}
