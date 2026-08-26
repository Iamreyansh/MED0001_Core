package com.nammamedmate.integration.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.integration.adapter.out.client.CashfreeHmac;
import com.nammamedmate.integration.adapter.out.client.StubCashfreeClient;
import com.nammamedmate.integration.adapter.out.client.StubCashfreePayoutClient;
import com.nammamedmate.integration.domain.CashfreeBeneficiary;
import com.nammamedmate.integration.domain.CashfreePaymentRecord;
import com.nammamedmate.integration.domain.CashfreePayoutRecord;
import com.nammamedmate.integration.domain.PaymentStatuses;
import com.nammamedmate.integration.domain.PayoutModes;
import com.nammamedmate.integration.domain.PayoutStatuses;
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

class CashfreeIntegrationServiceBranchTest {

  private static final String WHSEC = StubCashfreeClient.DEFAULT_WEBHOOK_SECRET;
  private static final Instant NOW = Instant.parse("2026-07-24T10:00:00Z");

  private InMemoryStores.Payments payments;
  private InMemoryStores.FundAccounts fundAccounts;
  private InMemoryStores.Payouts payouts;
  private List<String> events;
  private CashfreeIntegrationService service;
  private StubCashfreePayoutClient cashfreeX;

  @BeforeEach
  void setUp() {
    payments = new InMemoryStores.Payments();
    fundAccounts = new InMemoryStores.FundAccounts();
    payouts = new InMemoryStores.Payouts();
    events = new ArrayList<>();
    cashfreeX = new StubCashfreePayoutClient();
    service =
        new CashfreeIntegrationService(
            new StubCashfreeClient(WHSEC),
            cashfreeX,
            payments,
            fundAccounts,
            payouts,
            (t, a, i, p) -> events.add(t),
            new ObjectMapper(),
            Clock.fixed(NOW, ZoneOffset.UTC));
  }

  @Test
  void createOrderUnavailable() {
    CashfreeIntegrationService failing =
        new CashfreeIntegrationService(
            new StubCashfreeClient(WHSEC, true),
            cashfreeX,
            payments,
            fundAccounts,
            payouts,
            (t, a, i, p) -> {},
            new ObjectMapper(),
            Clock.fixed(NOW, ZoneOffset.UTC));
    assertThatThrownBy(() -> failing.createOrder(100, "INR", "r", Map.of()))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("CASHFREE_UNAVAILABLE");
  }

  @Test
  void fundAccountReuseAndReplace() {
    UUID entity = UUID.randomUUID();
    Map<String, Object> first =
        service.createBeneficiary(
            "RIDER", entity, "HDFC", "50100123456789", "HDFC0001234", "Rider");
    Map<String, Object> again =
        service.createBeneficiary(
            "RIDER", entity, "HDFC", "50100123456789", "HDFC0001234", "Rider");
    assertThat(again.get("beneficiary_id")).isEqualTo(first.get("beneficiary_id"));
    Map<String, Object> changed =
        service.createBeneficiary(
            "RIDER", entity, "ICICI", "50100999999999", "ICIC0001234", "Rider");
    assertThat(changed.get("beneficiary_id")).isNotEqualTo(first.get("beneficiary_id"));
    assertThat(changed.get("account_last4")).isEqualTo("9999");
  }

  @Test
  void payoutIdempotentByReferenceAndModeOverride() {
    CashfreeBeneficiary fa = seedFa();
    Map<String, Object> first =
        service.initiatePayout(fa.beneficiaryId(), 1000, "NEFT", "payout", "SAME-REF", Map.of());
    Map<String, Object> second =
        service.initiatePayout(fa.beneficiaryId(), 1000, "IMPS", "payout", "SAME-REF", Map.of());
    assertThat(second.get("cashfree_transfer_id")).isEqualTo(first.get("cashfree_transfer_id"));
    assertThat(first.get("mode")).isEqualTo("NEFT");
  }

  @Test
  void paymentFailedAndRefundAndPayoutProcessed() {
    UUID payId = UUID.randomUUID();
    payments.insert(
        new CashfreePaymentRecord(
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
        new CashfreePaymentRecord(
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

    CashfreeBeneficiary fa = seedFa();
    UUID pout = UUID.randomUUID();
    payouts.insert(
        new CashfreePayoutRecord(
            pout,
            "PHARMACY",
            fa.entityId(),
            fa.beneficiaryId(),
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
                service.createBeneficiary(
                    "X", UUID.randomUUID(), "b", "50100123456789", "HDFC0001234", "n"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
  }

  @Test
  void authorizedWithoutExistingOrderStillCaptures() {
    webhook(
        "{\"event\":\"payment.authorized\",\"payload\":{\"payment\":{\"entity\":{\"id\":\"pay_x\",\"order_id\":\"order_x\",\"amount\":200,\"method\":\"card\"}}}}");
    assertThat(payments.findByGatewayPaymentId("pay_x")).isPresent();
    assertThat(payments.findByGatewayPaymentId("pay_x").get().status())
        .isEqualTo(PaymentStatuses.CAPTURED);
  }

  @Test
  void payoutUnavailable() {
    CashfreeBeneficiary fa = seedFa();
    cashfreeX.setFailPayout(true);
    assertThatThrownBy(
            () ->
                service.initiatePayout(
                    fa.beneficiaryId(), 1000, null, "payout", "FAIL-REF", Map.of()))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("CASHFREE_PAYOUTS_UNAVAILABLE");
  }

  private CashfreeBeneficiary seedFa() {
    CashfreeBeneficiary fa =
        new CashfreeBeneficiary(
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
    service.handleWebhook(CashfreeHmac.hmacHex(WHSEC, json), body);
  }
}
