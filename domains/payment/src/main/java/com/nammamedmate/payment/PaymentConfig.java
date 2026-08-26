package com.nammamedmate.payment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.payment.adapter.out.client.LiveCashfreeGatewayClient;
import com.nammamedmate.payment.adapter.out.client.LiveCashfreePayoutClient;
import com.nammamedmate.payment.adapter.out.client.StubCashfreeGatewayClient;
import com.nammamedmate.payment.adapter.out.client.StubCashfreePayoutClient;
import com.nammamedmate.payment.adapter.out.persistence.LocalTaxFilingObjectStore;
import com.nammamedmate.payment.application.port.out.CashfreeGatewayPort;
import com.nammamedmate.payment.application.port.out.CashfreePayoutPort;
import com.nammamedmate.payment.application.port.out.CodFloatAlertPort;
import com.nammamedmate.payment.application.port.out.CodFloatPort;
import com.nammamedmate.payment.application.port.out.CustomerWalletPort;
import com.nammamedmate.payment.application.port.out.OrderLookupPort;
import com.nammamedmate.payment.application.port.out.OrderPaymentStatusPort;
import com.nammamedmate.payment.application.port.out.PharmacySettlementPort;
import com.nammamedmate.payment.application.port.out.RefundFinancePort;
import com.nammamedmate.payment.application.port.out.RefundNotificationPort;
import com.nammamedmate.payment.application.port.out.RiderPayoutNotificationPort;
import com.nammamedmate.payment.application.port.out.RiderPayoutPort;
import com.nammamedmate.payment.application.port.out.SettlementNotificationPort;
import com.nammamedmate.payment.application.port.out.TaxFilingObjectStore;
import com.nammamedmate.payment.application.port.out.TaxPharmacyProfilePort;
import com.nammamedmate.payment.application.port.out.WalletPort;
import com.nammamedmate.payment.domain.RiderPayoutStatuses;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@Configuration
public class PaymentConfig {

  /**
   * Fail-closed for staging/prod: never fall back to stub secrets that can forge verify/webhooks.
   */
  public static void validateCashfreeSecretsForDeployedProfile(
      String keyId, String keySecret, String webhookSecret, boolean deployedProfile) {
    if (!deployedProfile) {
      return;
    }
    if (blank(keyId) || blank(keySecret) || blank(webhookSecret)) {
      throw new IllegalStateException(
          "medmate.cashfree.app-id, secret-key, and webhook-secret must be set for staging/prod");
    }
    if (StubCashfreeGatewayClient.DEFAULT_KEY_SECRET.equals(keySecret)
        || StubCashfreeGatewayClient.DEFAULT_WEBHOOK_SECRET.equals(webhookSecret)
        || StubCashfreeGatewayClient.DEFAULT_KEY_ID.equals(keyId)
        || "cf_live_replace_me".equals(keyId)
        || "cf_test_replace_me".equals(keyId)
        || "replace_me".equals(keySecret)) {
      throw new IllegalStateException(
          "medmate.cashfree.* must not use stub/default secrets in staging/prod");
    }
  }

  /** Fail-closed CashfreePayout key pair for staging/prod. */
  public static void validateCashfreePayoutSecretsForDeployedProfile(
      String keyId, String keySecret, boolean deployedProfile) {
    if (!deployedProfile) {
      return;
    }
    if (blank(keyId) || blank(keySecret)) {
      throw new IllegalStateException(
          "medmate.cashfree.payouts-client-id and payouts-client-secret must be set for staging/prod");
    }
    if (StubCashfreePayoutClient.DEFAULT_KEY_ID.equals(keyId)
        || StubCashfreePayoutClient.DEFAULT_KEY_SECRET.equals(keySecret)
        || "cf_test_replace_me".equals(keyId)
        || "replace_me".equals(keySecret)) {
      throw new IllegalStateException(
          "medmate.cashfree.* must not use stub/default secrets in staging/prod");
    }
  }

  private static boolean blank(String s) {
    return s == null || s.isBlank();
  }

  private static boolean isDeployedProfile(Environment env) {
    if (env == null) {
      return false;
    }
    for (String profile : env.getActiveProfiles()) {
      if ("prod".equals(profile) || "staging".equals(profile)) {
        return true;
      }
    }
    return false;
  }

  @Bean
  @ConditionalOnMissingBean(CashfreeGatewayPort.class)
  CashfreeGatewayPort cashfreeGatewayPort(
      ObjectMapper objectMapper,
      Environment environment,
      @Value("${medmate.cashfree.app-id:}") String keyId,
      @Value("${medmate.cashfree.secret-key:}") String keySecret,
      @Value("${medmate.cashfree.webhook-secret:}") String webhookSecret) {
    boolean deployed = isDeployedProfile(environment);
    validateCashfreeSecretsForDeployedProfile(keyId, keySecret, webhookSecret, deployed);
    if (blank(keyId) || blank(keySecret) || blank(webhookSecret)) {
      return new StubCashfreeGatewayClient(
          blank(keyId) ? StubCashfreeGatewayClient.DEFAULT_KEY_ID : keyId,
          keySecret,
          webhookSecret);
    }
    return new LiveCashfreeGatewayClient(
        keyId, keySecret, webhookSecret, objectMapper, PaymentConfig::cashfreeHttpPost);
  }

  @Bean
  @ConditionalOnMissingBean(WalletPort.class)
  WalletPort stubPaymentWalletPort() {
    return (customerId, orderId, amountPaise, description) -> 0L;
  }

  @Bean
  @ConditionalOnMissingBean(CustomerWalletPort.class)
  CustomerWalletPort stubCustomerWalletPort() {
    return new CustomerWalletPort() {
      @Override
      public Map<String, Object> debit(
          UUID customerId, UUID orderId, long amountPaise, String idempotencyKey, String note) {
        return Map.of(
            "transaction_id",
            UUID.randomUUID(),
            "customer_id",
            customerId,
            "deducted_amount",
            BigDecimal.ZERO,
            "balance_before",
            BigDecimal.ZERO,
            "remaining_balance",
            BigDecimal.ZERO,
            "idempotency_key",
            idempotencyKey == null ? "" : idempotencyKey,
            "already_processed",
            false);
      }

      @Override
      public Map<String, Object> systemCredit(
          UUID customerId,
          long amountPaise,
          String reason,
          String referenceId,
          String note,
          String idempotencyKey) {
        return Map.of(
            "transaction_id",
            UUID.randomUUID(),
            "customer_id",
            customerId,
            "amount",
            BigDecimal.ZERO,
            "new_balance",
            BigDecimal.ZERO,
            "reason",
            reason == null ? "REFUND" : reason,
            "already_processed",
            false);
      }

      @Override
      public Map<String, Object> adminCredit(
          UUID adminId,
          UUID customerId,
          long amountPaise,
          String reason,
          String note,
          String referenceId,
          String idempotencyKey) {
        return systemCredit(customerId, amountPaise, reason, referenceId, note, idempotencyKey);
      }

      @Override
      public Map<String, Object> balance(UUID customerId) {
        return Map.of(
            "customer_id",
            customerId,
            "balance",
            BigDecimal.ZERO,
            "expiring_soon",
            Map.of("amount", BigDecimal.ZERO, "expires_within_days", 30));
      }

      @Override
      public TransactionsPage transactions(
          UUID customerId, Integer page, Integer limit, String type) {
        return new TransactionsPage(
            List.of(), 0, page == null ? 1 : page, limit == null ? 20 : limit);
      }
    };
  }

  @Bean
  @ConditionalOnMissingBean(OrderLookupPort.class)
  OrderLookupPort stubOrderLookupPort() {
    return orderId -> Optional.empty();
  }

  @Bean
  @ConditionalOnMissingBean(OrderPaymentStatusPort.class)
  OrderPaymentStatusPort stubOrderPaymentStatusPort() {
    return new OrderPaymentStatusPort() {
      @Override
      public void onCaptured(UUID orderId, String gatewayPaymentId) {
        // no-op until apps/api bridge
      }

      @Override
      public void onFailed(UUID orderId, String reason) {
        // no-op until apps/api bridge
      }
    };
  }

  @Bean
  @ConditionalOnMissingBean(CashfreePayoutPort.class)
  CashfreePayoutPort paymentCashfreePayoutPort(
      ObjectMapper objectMapper,
      Environment environment,
      @Value("${medmate.cashfree.payouts-client-id:}") String clientId,
      @Value("${medmate.cashfree.payouts-client-secret:}") String clientSecret) {
    boolean deployed = isDeployedProfile(environment);
    validateCashfreePayoutSecretsForDeployedProfile(clientId, clientSecret, deployed);
    if (blank(clientId) || blank(clientSecret)) {
      return new StubCashfreePayoutClient();
    }
    return new LiveCashfreePayoutClient(
        clientId, clientSecret, objectMapper, PaymentConfig::cashfreePayoutHttpPost);
  }

  @Bean
  @ConditionalOnMissingBean(SettlementNotificationPort.class)
  SettlementNotificationPort stubSettlementNotificationPort() {
    return new SettlementNotificationPort() {
      @Override
      public void settlementReleased(UUID pharmacyId, UUID settlementId, long netPaise) {
        // no-op until apps/api outbox bridge
      }

      @Override
      public void settlementHeld(UUID pharmacyId, UUID settlementId, String reason) {
        // no-op until apps/api outbox bridge
      }
    };
  }

  @Bean
  @ConditionalOnMissingBean(RefundNotificationPort.class)
  RefundNotificationPort stubRefundNotificationPort() {
    return (customerId, refundId, orderId, amountPaise) -> {
      // no-op until apps/api outbox bridge
    };
  }

  @Bean
  @ConditionalOnMissingBean(CodFloatAlertPort.class)
  CodFloatAlertPort stubCodFloatAlertPort() {
    return (reportId, reportDate, variancePaise, reconciliationStatus) -> {
      // no-op until apps/api outbox bridge
    };
  }

  @Bean
  @ConditionalOnMissingBean(CodFloatPort.class)
  CodFloatPort stubCodFloatPort() {
    return new CodFloatPort() {
      @Override
      public long floatLimitPaise() {
        return 200_000L;
      }

      @Override
      public FloatSnapshot floatBoard(
          UUID zoneId,
          boolean riskOnly,
          Instant dayStart,
          Instant dayEnd,
          long limitPaise,
          int page,
          int limit) {
        return new FloatSnapshot(List.of(), 0, 0, 0, 0, 0, 0);
      }

      @Override
      public DayAggregates aggregatesForDay(Instant dayStart, Instant dayEnd) {
        return new DayAggregates(0, 0, 0, 0, List.of());
      }

      @Override
      public Optional<ReportRecord> findReport(LocalDate reportDate) {
        return Optional.empty();
      }

      @Override
      public boolean tryClaimJob(UUID jobId, LocalDate reportDate, UUID triggeredBy, Instant now) {
        return false;
      }

      @Override
      public void completeReport(ReportRecord report) {}

      @Override
      public boolean hasCodDepositLedgerEntry(UUID depositId) {
        return false;
      }
    };
  }

  @Bean
  @ConditionalOnMissingBean(RefundFinancePort.class)
  RefundFinancePort stubRefundFinancePort() {
    return new RefundFinancePort() {
      @Override
      public Optional<RefundRecord> findById(UUID refundId) {
        return Optional.empty();
      }

      @Override
      public Optional<RefundRecord> findByGatewayRefundId(String gatewayRefundId) {
        return Optional.empty();
      }

      @Override
      public ListResult list(ListFilter filter) {
        return new ListResult(List.of(), 0);
      }

      @Override
      public ListResult listForCustomer(UUID customerId, int limit, int offset) {
        return new ListResult(List.of(), 0);
      }

      @Override
      public KpiSnapshot kpis(Instant dayStart, Instant dayEnd, Instant overdueBefore) {
        return new KpiSnapshot(0, 0, 0, 0, 0);
      }

      @Override
      public boolean claimForProcess(UUID refundId, UUID processedBy, String notes, Instant now) {
        return false;
      }

      @Override
      public boolean finalizeGatewayProcess(
          UUID refundId, String gatewayRefundId, LocalDate expectedBy, Instant now) {
        return false;
      }

      @Override
      public void attachGatewayRefundId(UUID refundId, String gatewayRefundId, Instant now) {}

      @Override
      public void markProcessFailed(UUID refundId, String reason, Instant now) {}

      @Override
      public boolean markCompleted(UUID refundId, Instant now) {
        return false;
      }

      @Override
      public boolean markWalletCompleted(
          UUID refundId, UUID walletTxId, UUID processedBy, String notes, Instant now) {
        return false;
      }
    };
  }

  @Bean
  @ConditionalOnMissingBean(RiderPayoutNotificationPort.class)
  RiderPayoutNotificationPort stubRiderPayoutNotificationPort() {
    return new RiderPayoutNotificationPort() {
      @Override
      public void payoutReleased(
          UUID riderId, UUID payoutId, long netPaise, String cashfreeTransferId) {
        // no-op until apps/api outbox bridge
      }

      @Override
      public void payoutFailed(UUID riderId, UUID payoutId, String error) {
        // no-op until apps/api outbox bridge
      }
    };
  }

  @Bean
  @ConditionalOnMissingBean(RiderPayoutPort.class)
  RiderPayoutPort stubRiderPayoutPort() {
    return new RiderPayoutPort() {
      @Override
      public Optional<PayoutRecord> findById(UUID payoutId) {
        return Optional.empty();
      }

      @Override
      public Optional<PayoutRecord> findByIdempotencyKey(String idempotencyKey) {
        return Optional.empty();
      }

      @Override
      public Optional<RiderSnapshot> findRider(UUID riderId) {
        return Optional.empty();
      }

      @Override
      public Optional<PaymentInstrument> findPaymentInstrument(UUID riderId) {
        return Optional.empty();
      }

      @Override
      public ListResult list(ListFilter filter) {
        return new ListResult(List.of(), 0);
      }

      @Override
      public SummarySnapshot summary(LocalDate cycleFrom, UUID zoneId) {
        return new SummarySnapshot(0, 0, 0, 0, 0, 0);
      }

      @Override
      public ListResult listForRider(UUID riderId, int limit, int offset) {
        return new ListResult(List.of(), 0);
      }

      @Override
      public List<EarningsEntry> listEarnings(
          UUID riderId, LocalDate from, LocalDate to, int limit, int offset) {
        return List.of();
      }

      @Override
      public long countEarnings(UUID riderId, LocalDate from, LocalDate to) {
        return 0L;
      }

      @Override
      public Optional<PayoutRecord> findByRiderAndCycle(
          UUID riderId, LocalDate cycleFrom, LocalDate cycleTo) {
        return Optional.empty();
      }

      @Override
      public long codFloatLimitPaise() {
        return RiderPayoutStatuses.DEFAULT_COD_FLOAT_LIMIT_PAISE;
      }

      @Override
      public boolean claimForRelease(
          UUID payoutId, UUID riderId, String idempotencyKey, Instant now) {
        return false;
      }

      @Override
      public boolean finalizeRelease(
          UUID payoutId,
          UUID releasedBy,
          Instant releasedAt,
          String cashfreeTransferId,
          String notes,
          String idempotencyKey,
          Instant now) {
        return false;
      }

      @Override
      public void scheduleRetry(
          UUID payoutId, String idempotencyKey, String error, Instant retryAt, Instant now) {}

      @Override
      public void markFailed(UUID payoutId, String idempotencyKey, String error, Instant now) {}

      @Override
      public void markBelowThreshold(UUID payoutId, String notes, Instant now) {}

      @Override
      public void adjustEarningsWallet(UUID riderId, long deltaPaise, Instant now) {}

      @Override
      public List<PayoutRecord> listPendingForBulk(
          long minPaiseInclusive, long maxPaiseInclusive, LocalDate cycleFrom, int limit) {
        return List.of();
      }
    };
  }

  @Bean
  @ConditionalOnMissingBean(TaxPharmacyProfilePort.class)
  TaxPharmacyProfilePort stubTaxPharmacyProfilePort() {
    return pharmacyId ->
        Optional.of(new TaxPharmacyProfilePort.PharmacyTaxProfile(pharmacyId, "", "", ""));
  }

  @Bean
  @ConditionalOnMissingBean(TaxFilingObjectStore.class)
  TaxFilingObjectStore localTaxFilingObjectStore() {
    return new LocalTaxFilingObjectStore();
  }

  @Bean
  @ConditionalOnMissingBean(PharmacySettlementPort.class)
  PharmacySettlementPort stubPharmacySettlementPort() {
    return new PharmacySettlementPort() {
      @Override
      public Optional<SettlementRecord> findById(UUID settlementId) {
        return Optional.empty();
      }

      @Override
      public Optional<SettlementRecord> findByIdempotencyKey(String idempotencyKey) {
        return Optional.empty();
      }

      @Override
      public ListResult list(ListFilter filter) {
        return new ListResult(List.of(), 0);
      }

      @Override
      public Totals totals(ListFilter filter) {
        return new Totals(0, 0, 0, 0);
      }

      @Override
      public KpiSnapshot kpis(Instant dayStartIst, Instant dayEndIst) {
        return new KpiSnapshot(0, 0, 0, 0);
      }

      @Override
      public Optional<BankSnapshot> findVerifiedBank(UUID pharmacyId) {
        return Optional.empty();
      }

      @Override
      public List<LineItem> lineItems(
          UUID pharmacyId, LocalDate cycleFrom, LocalDate cycleTo, BigDecimal commissionPct) {
        return List.of();
      }

      @Override
      public boolean claimForRelease(
          UUID settlementId, UUID pharmacyId, String idempotencyKey, Instant now) {
        return false;
      }

      @Override
      public boolean finalizeRelease(
          UUID settlementId,
          UUID releasedBy,
          Instant releasedAt,
          String cashfreeTransferId,
          String notes,
          String idempotencyKey,
          Instant now) {
        return false;
      }

      @Override
      public void markReleaseFailed(UUID settlementId, String idempotencyKey, Instant now) {}

      @Override
      public void markHeld(
          UUID settlementId, UUID heldBy, String reason, String notes, Instant heldAt) {}

      @Override
      public void markUnheld(UUID settlementId, UUID unheldBy, String notes, Instant unheldAt) {}

      @Override
      public void markBelowThreshold(UUID settlementId, String notes, Instant now) {}

      @Override
      public List<SettlementRecord> listPendingForBulk(long maxNetPaiseInclusive, int limit) {
        return List.of();
      }
    };
  }

  /** Live Cashfree HTTP — kept here so JaCoCo excludes Config. */
  static String cashfreeHttpPost(LiveCashfreeGatewayClient.Request request) {
    try {
      HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
      HttpRequest.Builder builder =
          HttpRequest.newBuilder(request.uri())
              .timeout(Duration.ofSeconds(10))
              .POST(HttpRequest.BodyPublishers.ofString(request.body()));
      for (Map.Entry<String, String> header : request.headers().entrySet()) {
        builder.header(header.getKey(), header.getValue());
      }
      HttpResponse<String> response =
          client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() >= 400) {
        throw new AppException("CASHFREE_ERROR", "Cashfree HTTP " + response.statusCode(), 502);
      }
      return response.body();
    } catch (AppException e) {
      throw e;
    } catch (IOException | InterruptedException e) {
      if (e instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      throw new AppException("CASHFREE_ERROR", "Cashfree request failed", 502);
    }
  }

  /** Live CashfreePayout HTTP — kept here so JaCoCo excludes Config. */
  static String cashfreePayoutHttpPost(LiveCashfreePayoutClient.Request request) {
    try {
      HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
      HttpRequest.Builder builder =
          HttpRequest.newBuilder(request.uri())
              .timeout(Duration.ofSeconds(10))
              .POST(HttpRequest.BodyPublishers.ofString(request.body()));
      for (Map.Entry<String, String> header : request.headers().entrySet()) {
        builder.header(header.getKey(), header.getValue());
      }
      HttpResponse<String> response =
          client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() >= 400) {
        throw new AppException(
            "CASHFREE_PAYOUT_FAILED", "CashfreePayout HTTP " + response.statusCode(), 502);
      }
      return response.body();
    } catch (AppException e) {
      throw e;
    } catch (IOException | InterruptedException e) {
      if (e instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      throw new AppException("CASHFREE_PAYOUT_FAILED", "CashfreePayout request failed", 502);
    }
  }
}
