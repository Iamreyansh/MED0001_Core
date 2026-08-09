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

class RazorpayIntegrationServiceAcTest {

  private static final String WHSEC = StubRazorpayClient.DEFAULT_WEBHOOK_SECRET;
  private static final Instant NOW = Instant.parse("2026-07-24T10:00:00Z");

  private InMemoryStores.Payments payments;
  private InMemoryStores.FundAccounts fundAccounts;
  private InMemoryStores.Payouts payouts;
  private List<String> eventTypes;
  private StubRazorpayClient razorpay;
  private StubRazorpayXClient razorpayX;
  private RazorpayIntegrationService service;

  @BeforeEach
  void setUp() {
    payments = new InMemoryStores.Payments();
    fundAccounts = new InMemoryStores.FundAccounts();
    payouts = new InMemoryStores.Payouts();
    eventTypes = new ArrayList<>();
    razorpay = new StubRazorpayClient(WHSEC);
    razorpayX = new StubRazorpayXClient();
    service =
        new RazorpayIntegrationService(
            razorpay,
            razorpayX,
            payments,
            fundAccounts,
            payouts,
            (type, agg, id, payload) -> eventTypes.add(type),
            new ObjectMapper(),
            Clock.fixed(NOW, ZoneOffset.UTC));
  }

  @Test
  void ac001_invalidSignatureReturnsInvalidSignature() {
    byte[] body = "{\"event\":\"payment.captured\"}".getBytes(StandardCharsets.UTF_8);
    assertThatThrownBy(() -> service.handleWebhook("bad-sig", body))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_SIGNATURE");
  }

  @Test
  void ac002_duplicatePaymentCapturedIgnored() {
    String payId = "pay_dup_1";
    UUID id = UUID.randomUUID();
    payments.insert(
        new RazorpayPaymentRecord(
            id,
            UUID.randomUUID(),
            "order_1",
            payId,
            50400,
            "INR",
            "upi",
            PaymentStatuses.CAPTURED,
            NOW,
            NOW));
    String payload =
        """
        {"event":"payment.captured","payload":{"payment":{"entity":{"id":"%s","order_id":"order_1","amount":50400}}}}
        """
            .formatted(payId);
    byte[] body = payload.getBytes(StandardCharsets.UTF_8);
    service.handleWebhook(sign(body), body);
    assertThat(payments.size()).isEqualTo(1);
    assertThat(eventTypes).isEmpty();
  }

  @Test
  void ac003_payoutModeAutoSelectImpsAndNeft() {
    RazorpayXFundAccount fa = seedFundAccount();
    Map<String, Object> imps =
        service.initiatePayout(
            fa.fundAccountId(), 20_000_000L, null, "payout", "REF-IMPS", Map.of());
    assertThat(imps.get("mode")).isEqualTo(PayoutModes.IMPS);

    Map<String, Object> neft =
        service.initiatePayout(
            fa.fundAccountId(), 20_000_001L, null, "payout", "REF-NEFT", Map.of());
    assertThat(neft.get("mode")).isEqualTo(PayoutModes.NEFT);
  }

  @Test
  void ac004_verifyUpiReturnsValidAndName() {
    Map<String, Object> data = service.verifyUpi("ravi.kumar@okicici");
    assertThat(data.get("valid")).isEqualTo(true);
    assertThat(data.get("name")).isEqualTo("RAVI KUMAR");
    assertThat(data.get("vpa")).isEqualTo("ravi.kumar@okicici");
  }

  @Test
  void ac005_fundAccountCreatesContactAndStoresLast4Only() {
    UUID entityId = UUID.randomUUID();
    Map<String, Object> data =
        service.createFundAccount(
            "PHARMACY", entityId, "HDFC Bank", "50100123456789", "HDFC0001234", "Apollo Pharmacy");
    assertThat(data.get("fund_account_id")).asString().startsWith("fa_stub_");
    assertThat(data.get("razorpayx_contact_id")).asString().startsWith("cont_stub_");
    assertThat(data.get("account_last4")).isEqualTo("6789");
    assertThat(data).doesNotContainKey("account_number");
  }

  @Test
  void ac006_failedPayoutRetriesOnceThenManualReview() {
    RazorpayXFundAccount fa = seedFundAccount();
    UUID payoutRecordId = UUID.randomUUID();
    Instant old = NOW.minusSeconds(3700);
    payouts.insert(
        new RazorpayXPayoutRecord(
            payoutRecordId,
            "PHARMACY",
            fa.entityId(),
            fa.fundAccountId(),
            "pout_fail_1",
            "REF-RETRY",
            10000L,
            PayoutModes.IMPS,
            PayoutStatuses.FAILED,
            0,
            old,
            old,
            "bank error"));

    razorpayX.setFailPayout(true);
    assertThat(service.retryFailedPayouts()).isEqualTo(1);
    assertThat(eventTypes).contains("PAYOUT_MANUAL_REVIEW");
    assertThat(payouts.findById(payoutRecordId)).isPresent();
    assertThat(payouts.findById(payoutRecordId).get().retryCount()).isEqualTo(1);
    assertThat(payouts.findById(payoutRecordId).get().status()).isEqualTo(PayoutStatuses.FAILED);
  }

  @Test
  void ac007_paymentAuthorizedCapturesAndEmitsPaymentCaptured() {
    payments.insert(
        new RazorpayPaymentRecord(
            UUID.randomUUID(),
            UUID.randomUUID(),
            "order_auth_1",
            null,
            50400,
            "INR",
            null,
            PaymentStatuses.CREATED,
            NOW,
            null));
    String payload =
        """
        {"event":"payment.authorized","payload":{"payment":{"entity":{"id":"pay_auth_1","order_id":"order_auth_1","amount":50400,"method":"upi"}}}}
        """;
    byte[] body = payload.getBytes(StandardCharsets.UTF_8);
    service.handleWebhook(sign(body), body);
    assertThat(payments.findByRazorpayPaymentId("pay_auth_1"))
        .isPresent()
        .get()
        .extracting(RazorpayPaymentRecord::status)
        .isEqualTo(PaymentStatuses.CAPTURED);
    assertThat(eventTypes).contains("PAYMENT_CAPTURED");
  }

  @Test
  void ac008_stubModeIsTestWhenKeysBlank() {
    assertThat(service.razorpayMode()).isEqualTo("TEST");
  }

  @Test
  void amountTooSmall() {
    assertThatThrownBy(() -> service.createOrder(99, "INR", "r", Map.of()))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("AMOUNT_TOO_SMALL");
  }

  @Test
  void createOrderHappyPath() {
    Map<String, Object> data =
        service.createOrder(
            50400, "INR", "ORD-1", Map.of("platform_order_id", UUID.randomUUID().toString()));
    assertThat(data.get("status")).isEqualTo("created");
    assertThat(data.get("amount_paise")).isEqualTo(50400L);
    assertThat(payments.size()).isEqualTo(1);
  }

  @Test
  void fundAccountInvalidIfscAndAccount() {
    UUID entityId = UUID.randomUUID();
    assertThatThrownBy(
            () ->
                service.createFundAccount(
                    "PHARMACY", entityId, "HDFC", "50100123456789", "BAD", "Name"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_IFSC");
    assertThatThrownBy(
            () ->
                service.createFundAccount(
                    "PHARMACY", entityId, "HDFC", "123", "HDFC0001234", "Name"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_ACCOUNT_NUMBER");
  }

  @Test
  void payoutFundAccountNotFound() {
    assertThatThrownBy(
            () -> service.initiatePayout("fa_missing", 1000, null, "payout", "R1", Map.of()))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("FUND_ACCOUNT_NOT_FOUND");
  }

  @Test
  void paymentCapturedCreatesRecordAndEvent() {
    String payload =
        """
        {"event":"payment.captured","payload":{"payment":{"entity":{"id":"pay_new","order_id":"order_new","amount":1000,"method":"upi"}}}}
        """;
    byte[] body = payload.getBytes(StandardCharsets.UTF_8);
    service.handleWebhook(sign(body), body);
    assertThat(payments.findByRazorpayPaymentId("pay_new")).isPresent();
    assertThat(eventTypes).contains("PAYMENT_CAPTURED");
  }

  @Test
  void payoutFailedWebhookSchedulesRetryWithoutAlertOnFirstFailure() {
    RazorpayXFundAccount fa = seedFundAccount();
    UUID id = UUID.randomUUID();
    payouts.insert(
        new RazorpayXPayoutRecord(
            id,
            "PHARMACY",
            fa.entityId(),
            fa.fundAccountId(),
            "pout_wh_fail",
            "REF-WH",
            5000L,
            PayoutModes.IMPS,
            PayoutStatuses.PROCESSING,
            0,
            NOW,
            null,
            null));
    String payload =
        """
        {"event":"payout.failed","payload":{"payout":{"entity":{"id":"pout_wh_fail","failure_reason":"bank"}}}}
        """;
    byte[] body = payload.getBytes(StandardCharsets.UTF_8);
    service.handleWebhook(sign(body), body);
    assertThat(payouts.findById(id).get().status()).isEqualTo(PayoutStatuses.FAILED);
    assertThat(eventTypes).doesNotContain("PAYOUT_MANUAL_REVIEW");
  }

  @Test
  void retrySuccessPath() {
    RazorpayXFundAccount fa = seedFundAccount();
    UUID id = UUID.randomUUID();
    payouts.insert(
        new RazorpayXPayoutRecord(
            id,
            "PHARMACY",
            fa.entityId(),
            fa.fundAccountId(),
            "pout_old",
            "REF-OK",
            5000L,
            PayoutModes.IMPS,
            PayoutStatuses.FAILED,
            0,
            NOW.minusSeconds(3700),
            NOW.minusSeconds(3700),
            "temp"));
    assertThat(service.retryFailedPayouts()).isEqualTo(1);
    assertThat(payouts.findById(id).get().status()).isEqualTo(PayoutStatuses.PROCESSING);
    assertThat(payouts.findById(id).get().retryCount()).isEqualTo(1);
    assertThat(eventTypes).doesNotContain("PAYOUT_MANUAL_REVIEW");
  }

  private RazorpayXFundAccount seedFundAccount() {
    RazorpayXFundAccount fa =
        new RazorpayXFundAccount(
            UUID.randomUUID(),
            "PHARMACY",
            UUID.randomUUID(),
            "cont_1",
            "fa_abc456",
            "HDFC",
            "6789",
            "HDFC0001234",
            "Apollo",
            true,
            NOW);
    fundAccounts.insert(fa);
    return fa;
  }

  private static String sign(byte[] body) {
    return RazorpayHmac.hmacHex(WHSEC, new String(body, StandardCharsets.UTF_8));
  }
}
