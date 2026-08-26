package com.nammamedmate.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.payment.adapter.out.client.LiveRazorpayGatewayClient;
import com.nammamedmate.payment.adapter.out.client.LiveRazorpayXPayoutClient;
import com.nammamedmate.payment.adapter.out.client.StubRazorpayGatewayClient;
import com.nammamedmate.payment.adapter.out.client.StubRazorpayXPayoutClient;
import com.nammamedmate.payment.application.port.out.CustomerWalletPort;
import com.nammamedmate.payment.application.port.out.OrderLookupPort;
import com.nammamedmate.payment.application.port.out.OrderPaymentStatusPort;
import com.nammamedmate.payment.application.port.out.PharmacySettlementPort;
import com.nammamedmate.payment.application.port.out.RazorpayGatewayPort;
import com.nammamedmate.payment.application.port.out.WalletPort;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;

class PaymentConfigTest {

  @Test
  void stubsWhenKeysMissingOnLocal() {
    PaymentConfig config = new PaymentConfig();
    Environment env = mock(Environment.class);
    when(env.getActiveProfiles()).thenReturn(new String[] {"local"});
    RazorpayGatewayPort port = config.razorpayGatewayPort(new ObjectMapper(), env, "", "", "");
    assertThat(port).isInstanceOf(StubRazorpayGatewayClient.class);

    WalletPort wallet = config.stubPaymentWalletPort();
    assertThat(wallet.debitForOrder(UUID.randomUUID(), UUID.randomUUID(), 100, "x")).isZero();

    CustomerWalletPort customerWallet = config.stubCustomerWalletPort();
    UUID cid = UUID.randomUUID();
    assertThat(customerWallet.debit(cid, UUID.randomUUID(), 1, "k", "n"))
        .containsKey("transaction_id");
    assertThat(customerWallet.systemCredit(cid, 1, "REFUND", "r", "n", "i"))
        .containsKey("transaction_id");
    assertThat(customerWallet.adminCredit(UUID.randomUUID(), cid, 1, "GOODWILL", "n", null, "i2"))
        .containsKey("transaction_id");
    assertThat(customerWallet.balance(cid)).containsKey("balance");
    assertThat(customerWallet.transactions(cid, null, null, null).transactions()).isEmpty();

    OrderLookupPort lookup = config.stubOrderLookupPort();
    assertThat(lookup.findById(UUID.randomUUID())).isEmpty();

    OrderPaymentStatusPort status = config.stubOrderPaymentStatusPort();
    status.onCaptured(UUID.randomUUID(), "pay");
    status.onFailed(UUID.randomUUID(), "reason");

    assertThat(config.paymentRazorpayXPayoutPort(new ObjectMapper(), env, "", ""))
        .isInstanceOf(StubRazorpayXPayoutClient.class);
    config
        .stubSettlementNotificationPort()
        .settlementReleased(UUID.randomUUID(), UUID.randomUUID(), 1);
    config
        .stubSettlementNotificationPort()
        .settlementHeld(UUID.randomUUID(), UUID.randomUUID(), "r");
    PharmacySettlementPort settlementStub = config.stubPharmacySettlementPort();
    assertThat(settlementStub.findById(UUID.randomUUID())).isEmpty();
    assertThat(settlementStub.findByIdempotencyKey("k")).isEmpty();
    assertThat(
            settlementStub
                .list(new PharmacySettlementPort.ListFilter(null, null, null, 10, 0))
                .total())
        .isZero();
    assertThat(
            settlementStub
                .totals(new PharmacySettlementPort.ListFilter(null, null, null, 10, 0))
                .totalGmvPaise())
        .isZero();
    assertThat(
            settlementStub
                .kpis(java.time.Instant.now(), java.time.Instant.now())
                .payoutDueTotalPaise())
        .isZero();
    assertThat(settlementStub.findVerifiedBank(UUID.randomUUID())).isEmpty();
    assertThat(
            settlementStub.lineItems(
                UUID.randomUUID(), java.time.LocalDate.now(), java.time.LocalDate.now(), null))
        .isEmpty();
    assertThat(
            settlementStub.claimForRelease(
                UUID.randomUUID(), UUID.randomUUID(), "k", java.time.Instant.now()))
        .isFalse();
    assertThat(
            settlementStub.finalizeRelease(
                UUID.randomUUID(),
                UUID.randomUUID(),
                java.time.Instant.now(),
                "p",
                null,
                "k",
                java.time.Instant.now()))
        .isFalse();
    settlementStub.markReleaseFailed(UUID.randomUUID(), "k", java.time.Instant.now());
    settlementStub.markHeld(
        UUID.randomUUID(), UUID.randomUUID(), "r", null, java.time.Instant.now());
    settlementStub.markUnheld(UUID.randomUUID(), UUID.randomUUID(), "n", java.time.Instant.now());
    settlementStub.markBelowThreshold(UUID.randomUUID(), "n", java.time.Instant.now());
    assertThat(settlementStub.listPendingForBulk(1, 10)).isEmpty();

    config
        .stubRefundNotificationPort()
        .refundCompleted(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1);
    config
        .stubCodFloatAlertPort()
        .varianceAlert(UUID.randomUUID(), java.time.LocalDate.now(), 1, "DISCREPANCY");
    var codFloat = config.stubCodFloatPort();
    assertThat(codFloat.floatLimitPaise()).isEqualTo(200_000L);
    assertThat(
            codFloat
                .floatBoard(
                    null, false, java.time.Instant.now(), java.time.Instant.now(), 200_000L, 1, 20)
                .total())
        .isZero();
    assertThat(
            codFloat
                .aggregatesForDay(java.time.Instant.now(), java.time.Instant.now())
                .totalCodOrders())
        .isZero();
    assertThat(codFloat.findReport(java.time.LocalDate.now())).isEmpty();
    assertThat(
            codFloat.tryClaimJob(
                UUID.randomUUID(), java.time.LocalDate.now(), null, java.time.Instant.now()))
        .isFalse();
    codFloat.completeReport(
        new com.nammamedmate.payment.application.port.out.CodFloatPort.ReportRecord(
            UUID.randomUUID(),
            java.time.LocalDate.now(),
            0,
            0,
            0,
            0,
            0,
            0,
            null,
            "BALANCED",
            false,
            java.time.Instant.now(),
            null,
            "[]"));
    assertThat(codFloat.hasCodDepositLedgerEntry(UUID.randomUUID())).isFalse();
    var refundStub = config.stubRefundFinancePort();
    assertThat(refundStub.findById(UUID.randomUUID())).isEmpty();
    assertThat(refundStub.findByRazorpayRefundId("x")).isEmpty();
    assertThat(
            refundStub
                .list(
                    new com.nammamedmate.payment.application.port.out.RefundFinancePort.ListFilter(
                        null, null, null, 10, 0))
                .total())
        .isZero();
    assertThat(refundStub.listForCustomer(UUID.randomUUID(), 10, 0).total()).isZero();
    assertThat(
            refundStub
                .kpis(java.time.Instant.now(), java.time.Instant.now(), java.time.Instant.now())
                .pendingCount())
        .isZero();
    assertThat(
            refundStub.claimForProcess(
                UUID.randomUUID(), UUID.randomUUID(), "n", java.time.Instant.now()))
        .isFalse();
    assertThat(
            refundStub.finalizeGatewayProcess(
                UUID.randomUUID(), "rfnd", java.time.LocalDate.now(), java.time.Instant.now()))
        .isFalse();
    refundStub.markProcessFailed(UUID.randomUUID(), "r", java.time.Instant.now());
    assertThat(refundStub.markCompleted(UUID.randomUUID(), java.time.Instant.now())).isFalse();
    assertThat(
            refundStub.markWalletCompleted(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                "n",
                java.time.Instant.now()))
        .isFalse();
  }

  @Test
  void liveWhenKeysConfigured() {
    PaymentConfig config = new PaymentConfig();
    Environment env = mock(Environment.class);
    when(env.getActiveProfiles()).thenReturn(new String[] {"local"});
    RazorpayGatewayPort port =
        config.razorpayGatewayPort(new ObjectMapper(), env, "rzp_live", "secret", "whsec");
    assertThat(port).isInstanceOf(LiveRazorpayGatewayClient.class);
    assertThat(config.paymentRazorpayXPayoutPort(new ObjectMapper(), env, "rzp_x", "xsecret"))
        .isInstanceOf(LiveRazorpayXPayoutClient.class);
  }

  @Test
  void deployedRejectsBlankAndStubSecrets() {
    PaymentConfig.validateRazorpaySecretsForDeployedProfile("id", "sec", "wh", false);
    assertThatThrownBy(
            () -> PaymentConfig.validateRazorpaySecretsForDeployedProfile("", "s", "w", true))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(
            () ->
                PaymentConfig.validateRazorpaySecretsForDeployedProfile(
                    StubRazorpayGatewayClient.DEFAULT_KEY_ID,
                    StubRazorpayGatewayClient.DEFAULT_KEY_SECRET,
                    StubRazorpayGatewayClient.DEFAULT_WEBHOOK_SECRET,
                    true))
        .isInstanceOf(IllegalStateException.class);

    PaymentConfig.validateRazorpayXSecretsForDeployedProfile("id", "sec", false);
    assertThatThrownBy(
            () -> PaymentConfig.validateRazorpayXSecretsForDeployedProfile("", "s", true))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(
            () ->
                PaymentConfig.validateRazorpayXSecretsForDeployedProfile(
                    StubRazorpayXPayoutClient.DEFAULT_KEY_ID,
                    StubRazorpayXPayoutClient.DEFAULT_KEY_SECRET,
                    true))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(
            () ->
                PaymentConfig.validateRazorpaySecretsForDeployedProfile(
                    "rzp_test_replace_me", "replace_me", "wh", true))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(
            () ->
                PaymentConfig.validateRazorpayXSecretsForDeployedProfile(
                    "rzp_test_replace_me", "replace_me", true))
        .isInstanceOf(IllegalStateException.class);

    Environment env = mock(Environment.class);
    when(env.getActiveProfiles()).thenReturn(new String[] {"prod"});
    PaymentConfig config = new PaymentConfig();
    assertThatThrownBy(() -> config.razorpayGatewayPort(new ObjectMapper(), env, "", "", ""))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> config.paymentRazorpayXPayoutPort(new ObjectMapper(), env, "", ""))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void httpPostHelpers() {
    assertThatThrownBy(
            () ->
                PaymentConfig.razorpayHttpPost(
                    new LiveRazorpayGatewayClient.Request(
                        java.net.URI.create("http://127.0.0.1:1"), java.util.Map.of(), "{}")))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("RAZORPAY_ERROR");
    assertThatThrownBy(
            () ->
                PaymentConfig.razorpayXHttpPost(
                    new LiveRazorpayXPayoutClient.Request(
                        java.net.URI.create("http://127.0.0.1:1"), java.util.Map.of(), "{}")))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("RAZORPAY_PAYOUT_FAILED");
  }
}
