package com.nammamedmate.customer.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nammamedmate.customer.application.port.out.WalletStore.WalletRecord;
import com.nammamedmate.customer.application.port.out.WalletStore.WalletTxRecord;
import com.nammamedmate.customer.domain.WalletTxType;
import com.nammamedmate.customer.support.CustomerTestFixtures;
import com.nammamedmate.customer.support.FakeCustomerProfileStore;
import com.nammamedmate.customer.support.FakeWalletStore;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.kernel.ratelimit.InMemoryRateLimiter;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class WalletServiceTest {

  private static final Instant NOW = Instant.parse("2026-07-26T02:00:00Z");
  private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

  private FakeWalletStore wallets;
  private FakeCustomerProfileStore profiles;
  private InMemoryRateLimiter rateLimiter;
  private WalletService service;
  private UUID customerId;
  private MedmatePrincipal customer;
  private MedmatePrincipal financeAdmin;

  @BeforeEach
  void setUp() {
    wallets = new FakeWalletStore();
    profiles = new FakeCustomerProfileStore();
    rateLimiter = new InMemoryRateLimiter(CLOCK);
    service = new WalletService(wallets, profiles, rateLimiter, CLOCK, 100_000L);
    customerId = Ids.newId();
    profiles.saveProfile(CustomerTestFixtures.customer(customerId));
    customer = new MedmatePrincipal(customerId, AuthRole.CUSTOMER, null, TokenScope.FULL, "j");
    financeAdmin =
        new MedmatePrincipal(Ids.newId(), AuthRole.ADMIN_FINANCE, null, TokenScope.FULL, "a");
    wallets.insertWallet(new WalletRecord(Ids.newId(), customerId, 0, 0, 0, 0, NOW, NOW));
  }

  private static String key() {
    return Ids.newId().toString();
  }

  private static WalletService.AdminCreditCommand credit(
      Object amount, String reason, String note, String referenceId) {
    return new WalletService.AdminCreditCommand(amount, reason, note, referenceId, key());
  }

  @Test
  void getMyWallet_newCustomer_showsZeroBalances() {
    Map<String, Object> view = service.getMyWallet(customer);

    assertThat(view)
        .containsEntry("balance", new BigDecimal("0.00"))
        .containsEntry("lifetime_credited", new BigDecimal("0.00"))
        .containsEntry("lifetime_debited", new BigDecimal("0.00"))
        .containsEntry("currency", "INR");
  }

  @Test
  void adminCredit_goodwill_setsExpiresAt365DaysAndBalanceAfter() {
    Map<String, Object> result =
        service.adminCredit(
            financeAdmin, customerId, credit(100, "GOODWILL", "Apology credit", null));

    assertThat(result.get("amount_credited")).isEqualTo(new BigDecimal("100.00"));
    assertThat(result.get("new_balance")).isEqualTo(new BigDecimal("100.00"));
    assertThat(result.get("reason")).isEqualTo("GOODWILL");
    Instant expires = (Instant) result.get("expires_at");
    assertThat(expires).isEqualTo(NOW.plus(365, ChronoUnit.DAYS));

    WalletTxRecord tx = wallets.allTransactions().getFirst();
    assertThat(tx.type()).isEqualTo(WalletTxType.CREDIT);
    assertThat(tx.balanceAfterPaise()).isEqualTo(10_000L);
    assertThat(tx.expiresAt()).isEqualTo(NOW.plus(365, ChronoUnit.DAYS));
    assertThat(wallets.syncedBalance(customerId)).isEqualTo(10_000L);
  }

  @Test
  void adminCredit_exceedsLimit_returns422() {
    assertThatThrownBy(
            () ->
                service.adminCredit(
                    financeAdmin, customerId, credit(1500, "GOODWILL", "Too much", null)))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("ADMIN_CREDIT_EXCEEDS_LIMIT");
  }

  @Test
  void adminCredit_customerMissing_returns404() {
    assertThatThrownBy(
            () ->
                service.adminCredit(financeAdmin, Ids.newId(), credit(10, "REFUND", "note", null)))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("CUSTOMER_NOT_FOUND");
  }

  @Test
  void adminCredit_validationBranches() {
    assertThatThrownBy(
            () -> service.adminCredit(financeAdmin, customerId, credit(null, "REFUND", "n", null)))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(
            () -> service.adminCredit(financeAdmin, customerId, credit(-1, "REFUND", "n", null)))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(
            () -> service.adminCredit(financeAdmin, customerId, credit(1.001, "REFUND", "n", null)))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(
            () -> service.adminCredit(financeAdmin, customerId, credit(10, "NOPE", "n", null)))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_REASON");
    assertThatThrownBy(
            () -> service.adminCredit(financeAdmin, customerId, credit(10, "REFUND", "  ", null)))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(
            () ->
                service.adminCredit(
                    financeAdmin, customerId, credit(10, "REFUND", "n", "x".repeat(256))))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(
            () ->
                service.adminCredit(
                    financeAdmin, customerId, credit(10, "REFUND", "n".repeat(501), null)))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
  }

  @Test
  void debitForOrder_appliesFullBalanceUpToOrderTotal() {
    service.adminCredit(financeAdmin, customerId, credit(200, "PROMOTIONAL", "promo", null));

    Map<String, Object> result =
        service.debitForOrder(customerId, Ids.newId(), 35_000L, "Payment for order #ORD-1");

    assertThat(result.get("amount_debited")).isEqualTo(new BigDecimal("200.00"));
    assertThat(result.get("new_balance")).isEqualTo(new BigDecimal("0.00"));
    WalletTxRecord debit =
        wallets.allTransactions().stream()
            .filter(t -> t.type() == WalletTxType.DEBIT)
            .findFirst()
            .orElseThrow();
    assertThat(debit.balanceAfterPaise()).isZero();
    assertThat(debit.reason()).isEqualTo("ORDER_PAYMENT");
  }

  @Test
  void debitForOrder_zeroBalance_noTransaction() {
    Map<String, Object> result = service.debitForOrder(customerId, Ids.newId(), 1000L, null);

    assertThat(result.get("transaction_id")).isNull();
    assertThat(result.get("amount_debited")).isEqualTo(new BigDecimal("0.00"));
  }

  @Test
  void expireCredits_insertsExpiredAndDecrementsBalance() {
    UUID walletId = wallets.findByCustomerId(customerId).orElseThrow().id();
    wallets.updateWallet(new WalletRecord(walletId, customerId, 5_000L, 5_000L, 0, 1, NOW, NOW), 0);
    UUID creditId = Ids.newId();
    wallets.insertTransaction(
        new WalletTxRecord(
            creditId,
            walletId,
            WalletTxType.CREDIT,
            5_000L,
            5_000L,
            "GOODWILL",
            "old",
            null,
            null,
            null,
            NOW.minus(366, ChronoUnit.DAYS),
            5_000L,
            NOW.minus(366, ChronoUnit.DAYS)));

    int count = service.expireCredits();

    assertThat(count).isEqualTo(1);
    assertThat(wallets.findById(walletId).orElseThrow().balancePaise()).isZero();
    assertThat(wallets.allTransactions().stream().anyMatch(t -> t.type() == WalletTxType.EXPIRED))
        .isTrue();
  }

  @Test
  void listMyTransactions_filtersByTypeCredit() {
    service.adminCredit(financeAdmin, customerId, credit(50, "REFUND", "r1", "ord-1"));
    service.debitForOrder(customerId, Ids.newId(), 2_000L, "partial");

    WalletService.TxPage page = service.listMyTransactions(customer, 1, 20, null, null, "CREDIT");

    assertThat(page.data()).hasSize(1);
    assertThat(page.data().getFirst()).containsEntry("type", "CREDIT");
    assertThat(page.meta().total()).isEqualTo(1);
  }

  @Test
  void listMyTransactions_invalidTypeAndSort() {
    assertThatThrownBy(() -> service.listMyTransactions(customer, 1, 20, "amount", null, null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> service.listMyTransactions(customer, 1, 20, null, null, "NOPE"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
  }

  @Test
  void getMyWallet_includesExpiringSoon() {
    UUID walletId = wallets.findByCustomerId(customerId).orElseThrow().id();
    wallets.updateWallet(new WalletRecord(walletId, customerId, 5_000L, 5_000L, 0, 1, NOW, NOW), 0);
    Instant expires = NOW.plus(10, ChronoUnit.DAYS);
    wallets.insertTransaction(
        new WalletTxRecord(
            Ids.newId(),
            walletId,
            WalletTxType.CREDIT,
            5_000L,
            5_000L,
            "REFUND",
            "soon",
            null,
            null,
            null,
            expires,
            5_000L,
            NOW));

    Map<String, Object> view = service.getMyWallet(customer);

    @SuppressWarnings("unchecked")
    Map<String, Object> soon = (Map<String, Object>) view.get("expiring_soon");
    assertThat(soon.get("amount")).isEqualTo(new BigDecimal("50.00"));
    assertThat(soon.get("expires_before")).isEqualTo(expires);
  }

  @Test
  void parsePositiveAmountPaise_acceptsStringAndBigDecimal() {
    assertThat(WalletService.parsePositiveAmountPaise("12.50")).isEqualTo(1250L);
    assertThat(WalletService.parsePositiveAmountPaise(new BigDecimal("1.00"))).isEqualTo(100L);
    assertThatThrownBy(() -> WalletService.parsePositiveAmountPaise("abc"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> WalletService.parsePositiveAmountPaise(Map.of()))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
  }

  @Test
  void unauthorizedRoles() {
    assertThatThrownBy(() -> service.getMyWallet(null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("UNAUTHORIZED");
    assertThatThrownBy(
            () -> service.adminCredit(customer, customerId, credit(1, "REFUND", "n", null)))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("UNAUTHORIZED");
  }

  @Test
  void rateLimited_getWallet() {
    InMemoryRateLimiter tight = new InMemoryRateLimiter(CLOCK);
    WalletService limited = new WalletService(wallets, profiles, tight, CLOCK, 100_000L);
    for (int i = 0; i < 30; i++) {
      limited.getMyWallet(customer);
    }
    assertThatThrownBy(() -> limited.getMyWallet(customer))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("RATE_LIMITED");
  }

  @Test
  void debitForOrder_coversEdgeBranches() {
    assertThatThrownBy(() -> service.debitForOrder(Ids.newId(), Ids.newId(), 100, "x"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("CUSTOMER_NOT_FOUND");

    service.adminCredit(financeAdmin, customerId, credit(10, "REFUND", "r", null));
    Map<String, Object> result = service.debitForOrder(customerId, null, 50_000L, "   ");
    assertThat(result.get("amount_debited")).isEqualTo(new BigDecimal("10.00"));
    assertThat(
            wallets.allTransactions().stream()
                .filter(t -> t.type() == WalletTxType.DEBIT)
                .findFirst()
                .orElseThrow()
                .description())
        .isEqualTo("Payment for order");

    service.adminCredit(financeAdmin, customerId, credit(5, "REFUND", "r2", null));
    service.debitForOrder(customerId, Ids.newId(), 50_000L, "d".repeat(600));
    assertThat(
            wallets.allTransactions().stream()
                .filter(t -> t.type() == WalletTxType.DEBIT)
                .reduce((a, b) -> b)
                .orElseThrow()
                .description())
        .hasSize(500);
  }

  @Test
  void debitForOrder_insufficientOpenCredits_throws() {
    UUID walletId = wallets.findByCustomerId(customerId).orElseThrow().id();
    wallets.updateWallet(new WalletRecord(walletId, customerId, 1_000L, 0, 0, 1, NOW, NOW), 0);
    assertThatThrownBy(() -> service.debitForOrder(customerId, Ids.newId(), 500L, "x"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INSUFFICIENT_WALLET_BALANCE");
  }

  @Test
  void expireCredits_skipsUnprogressableAndMissingWallet() {
    assertThat(service.expireCredits()).isZero();

    UUID orphanCredit = Ids.newId();
    wallets.insertTransaction(
        new WalletTxRecord(
            orphanCredit,
            Ids.newId(),
            WalletTxType.CREDIT,
            100,
            100,
            "GOODWILL",
            "orphan",
            null,
            null,
            null,
            NOW.minusSeconds(1),
            100L,
            NOW.minusSeconds(1)));
    assertThat(service.expireCredits()).isZero();

    UUID walletId = wallets.findByCustomerId(customerId).orElseThrow().id();
    wallets.insertTransaction(
        new WalletTxRecord(
            Ids.newId(),
            walletId,
            WalletTxType.CREDIT,
            100,
            100,
            "GOODWILL",
            "null-remaining",
            null,
            null,
            null,
            NOW.minusSeconds(1),
            null,
            NOW.minusSeconds(1)));
    assertThat(service.expireCredits()).isZero(); // null remaining → skip

    wallets.insertTransaction(
        new WalletTxRecord(
            Ids.newId(),
            walletId,
            WalletTxType.CREDIT,
            50,
            50,
            "GOODWILL",
            "future",
            null,
            null,
            null,
            NOW.plusSeconds(3600),
            50L,
            NOW));
    // Manually put expired with remaining; then clear remaining before expire re-reads
    UUID creditId = Ids.newId();
    wallets.insertTransaction(
        new WalletTxRecord(
            creditId,
            walletId,
            WalletTxType.CREDIT,
            25,
            25,
            "GOODWILL",
            "race",
            null,
            null,
            null,
            NOW.minusSeconds(10),
            25L,
            NOW.minusSeconds(10)));
    wallets.updateCreditRemaining(creditId, 25L, 0L);
    assertThat(service.expireCredits()).isZero();
  }

  @Test
  void requireWallet_createsWhenMissing() {
    UUID other = Ids.newId();
    profiles.saveProfile(CustomerTestFixtures.customer(other));
    MedmatePrincipal otherPrincipal =
        new MedmatePrincipal(other, AuthRole.CUSTOMER, null, TokenScope.FULL, "j2");
    Map<String, Object> view = service.getMyWallet(otherPrincipal);
    assertThat(view.get("wallet_id")).isNotNull();
  }

  @Test
  void createWallet_duplicateRecoversExisting() {
    UUID other = Ids.newId();
    profiles.saveProfile(CustomerTestFixtures.customer(other));
    UUID existingId = Ids.newId();
    FakeWalletStore racing =
        new FakeWalletStore() {
          private boolean firstFind = true;

          @Override
          public java.util.Optional<WalletRecord> findByCustomerId(UUID customerId) {
            if (firstFind) {
              firstFind = false;
              return java.util.Optional.empty();
            }
            return java.util.Optional.of(
                new WalletRecord(existingId, customerId, 0, 0, 0, 0, NOW, NOW));
          }

          @Override
          public WalletRecord insertWallet(WalletRecord wallet) {
            throw new IllegalStateException("duplicate");
          }
        };
    WalletService racingService = new WalletService(racing, profiles, rateLimiter, CLOCK, 100_000L);
    MedmatePrincipal otherPrincipal =
        new MedmatePrincipal(other, AuthRole.CUSTOMER, null, TokenScope.FULL, "j2");
    assertThat(racingService.getMyWallet(otherPrincipal).get("wallet_id")).isEqualTo(existingId);
  }

  @Test
  void createWallet_duplicateMissing_throws404() {
    UUID other = Ids.newId();
    FakeWalletStore racing =
        new FakeWalletStore() {
          @Override
          public java.util.Optional<WalletRecord> findByCustomerId(UUID customerId) {
            return java.util.Optional.empty();
          }

          @Override
          public WalletRecord insertWallet(WalletRecord wallet) {
            throw new IllegalStateException("duplicate");
          }
        };
    WalletService racingService = new WalletService(racing, profiles, rateLimiter, CLOCK, 100_000L);
    MedmatePrincipal otherPrincipal =
        new MedmatePrincipal(other, AuthRole.CUSTOMER, null, TokenScope.FULL, "j2");
    assertThatThrownBy(() -> racingService.getMyWallet(otherPrincipal))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("CUSTOMER_NOT_FOUND");
  }

  @Test
  void adminWalletSummary_withLedgerAndFallback() {
    Map<String, Object> empty = service.adminWalletSummary(customerId);
    assertThat(empty.get("lifetime_credited")).isEqualTo(new BigDecimal("0.00"));

    service.adminCredit(financeAdmin, customerId, credit(25.5, "PROMOTIONAL", "promo", "ref"));
    Map<String, Object> filled = service.adminWalletSummary(customerId);
    assertThat(filled.get("balance")).isEqualTo(new BigDecimal("25.50"));
    assertThat(filled.get("lifetime_credited")).isEqualTo(new BigDecimal("25.50"));

    Map<String, Object> fallback = service.adminWalletSummary(Ids.newId());
    assertThat(fallback.get("balance")).isEqualTo(new BigDecimal("0.00"));
  }

  @Test
  void consumeCreditsFifo_skipsZeroRemainingCredits() {
    UUID walletId = wallets.findByCustomerId(customerId).orElseThrow().id();
    wallets.insertTransaction(
        new WalletTxRecord(
            Ids.newId(),
            walletId,
            WalletTxType.CREDIT,
            100,
            100,
            "REFUND",
            "spent",
            null,
            null,
            null,
            NOW.plus(365, ChronoUnit.DAYS),
            0L,
            NOW));
    service.adminCredit(financeAdmin, customerId, credit(30, "REFUND", "open", null));
    Map<String, Object> result = service.debitForOrder(customerId, Ids.newId(), 5_000L, "pay");
    assertThat(result.get("amount_debited")).isEqualTo(new BigDecimal("30.00"));
  }

  @Test
  void parsePositiveAmountPaise_scaleUnnecessaryFails() {
    // 1.0 with scale that can't convert cleanly via UNNECESSARY after movePointRight
    assertThat(WalletService.parsePositiveAmountPaise(1)).isEqualTo(100L);
    assertThatThrownBy(() -> WalletService.parsePositiveAmountPaise(new BigDecimal("1.001")))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
  }

  @Test
  void listMyTransactions_defaultsToDescOrder() {
    service.adminCredit(financeAdmin, customerId, credit(1, "REFUND", "older", null));
    WalletService.TxPage page = service.listMyTransactions(customer, null, null, null, null, null);
    assertThat(page.data()).isNotEmpty();
    assertThat(page.meta().limit()).isEqualTo(20);
    WalletService.TxPage blankOrder =
        service.listMyTransactions(customer, null, null, null, "  ", null);
    assertThat(blankOrder.data()).isNotEmpty();
  }

  @Test
  void listTransactions_explicitAscStillWorks() {
    service.adminCredit(financeAdmin, customerId, credit(1, "REFUND", "a", null));
    WalletService.TxPage page =
        service.listMyTransactions(customer, null, null, "created_at", "asc", null);
    assertThat(page.data()).isNotEmpty();
  }

  @Test
  void adminCredit_replaysSameIdempotencyKey() {
    String idem = key();
    var cmd = new WalletService.AdminCreditCommand(40, "GOODWILL", "once", null, idem);
    Map<String, Object> first = service.adminCredit(financeAdmin, customerId, cmd);
    Map<String, Object> second = service.adminCredit(financeAdmin, customerId, cmd);
    assertThat(second.get("transaction_id")).isEqualTo(first.get("transaction_id"));
    assertThat(wallets.allTransactions()).hasSize(1);
    assertThat(wallets.findByCustomerId(customerId).orElseThrow().balancePaise()).isEqualTo(4_000L);
  }

  @Test
  void adminCredit_replaysWhenKeyAppearsAfterLock() {
    String idem = key();
    WalletTxRecord existing =
        new WalletTxRecord(
            Ids.newId(),
            wallets.findByCustomerId(customerId).orElseThrow().id(),
            WalletTxType.CREDIT,
            700L,
            700L,
            "REFUND",
            "after-lock",
            null,
            idem,
            financeAdmin.subject(),
            NOW.plus(365, ChronoUnit.DAYS),
            700L,
            NOW);
    FakeWalletStore racing =
        new FakeWalletStore() {
          private int finds;

          @Override
          public java.util.Optional<WalletTxRecord> findByIdempotencyKey(String key) {
            finds++;
            return finds == 1 ? java.util.Optional.empty() : java.util.Optional.of(existing);
          }
        };
    racing.insertWallet(wallets.findByCustomerId(customerId).orElseThrow());
    WalletService racingService = new WalletService(racing, profiles, rateLimiter, CLOCK, 100_000L);
    Map<String, Object> result =
        racingService.adminCredit(
            financeAdmin,
            customerId,
            new WalletService.AdminCreditCommand(7, "REFUND", "n", null, idem));
    assertThat(result.get("transaction_id")).isEqualTo(existing.id());
    assertThat(racing.allTransactions()).isEmpty();
  }

  @Test
  void adminCredit_duplicateKeyRace_replaysExisting() {
    String idem = key();
    WalletTxRecord existing =
        new WalletTxRecord(
            Ids.newId(),
            wallets.findByCustomerId(customerId).orElseThrow().id(),
            WalletTxType.CREDIT,
            500L,
            500L,
            "REFUND",
            "raced",
            null,
            idem,
            financeAdmin.subject(),
            NOW.plus(365, ChronoUnit.DAYS),
            500L,
            NOW);
    FakeWalletStore racing =
        new FakeWalletStore() {
          @Override
          public java.util.Optional<WalletTxRecord> findByIdempotencyKey(String key) {
            if (allTransactions().isEmpty()) {
              return java.util.Optional.empty();
            }
            return super.findByIdempotencyKey(key);
          }

          @Override
          public WalletTxRecord insertTransaction(WalletTxRecord tx) {
            // Simulate concurrent insert winning the unique index.
            super.insertTransaction(existing);
            throw new org.springframework.dao.DuplicateKeyException("race");
          }
        };
    racing.insertWallet(wallets.findByCustomerId(customerId).orElseThrow());
    WalletService racingService = new WalletService(racing, profiles, rateLimiter, CLOCK, 100_000L);
    Map<String, Object> result =
        racingService.adminCredit(
            financeAdmin,
            customerId,
            new WalletService.AdminCreditCommand(5, "REFUND", "n", null, idem));
    assertThat(result.get("transaction_id")).isEqualTo(existing.id());
  }

  @Test
  void adminCredit_duplicateKeyMissingRow_rethrows() {
    String idem = key();
    FakeWalletStore racing =
        new FakeWalletStore() {
          @Override
          public WalletTxRecord insertTransaction(WalletTxRecord tx) {
            throw new org.springframework.dao.DuplicateKeyException("race");
          }
        };
    racing.insertWallet(wallets.findByCustomerId(customerId).orElseThrow());
    WalletService racingService = new WalletService(racing, profiles, rateLimiter, CLOCK, 100_000L);
    assertThatThrownBy(
            () ->
                racingService.adminCredit(
                    financeAdmin,
                    customerId,
                    new WalletService.AdminCreditCommand(5, "REFUND", "n", null, idem)))
        .isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
  }

  @Test
  void expireCredits_casLostWhileListed_skips() {
    UUID walletId = wallets.findByCustomerId(customerId).orElseThrow().id();
    wallets.updateWallet(new WalletRecord(walletId, customerId, 1_000L, 1_000L, 0, 1, NOW, NOW), 0);
    UUID creditId = Ids.newId();
    WalletTxRecord open =
        new WalletTxRecord(
            creditId,
            walletId,
            WalletTxType.CREDIT,
            1_000L,
            1_000L,
            "GOODWILL",
            "listed",
            null,
            null,
            null,
            NOW.minusSeconds(1),
            1_000L,
            NOW.minusSeconds(1));
    FakeWalletStore flaky =
        new FakeWalletStore() {
          @Override
          public java.util.List<WalletTxRecord> findExpiredOpenCredits(
              java.time.Instant now, int limit) {
            return java.util.List.of(open);
          }

          @Override
          public boolean updateCreditRemaining(
              UUID creditTxId, long expectedRemaining, long remainingPaise) {
            return false;
          }
        };
    flaky.insertWallet(wallets.findByCustomerId(customerId).orElseThrow());
    WalletService flakyService = new WalletService(flaky, profiles, rateLimiter, CLOCK, 100_000L);
    assertThat(flakyService.expireCredits()).isZero();
  }

  @Test
  void debitForOrder_casLostOnCredit_throws() {
    FakeWalletStore flaky =
        new FakeWalletStore() {
          @Override
          public boolean updateCreditRemaining(
              UUID creditTxId, long expectedRemaining, long remainingPaise) {
            return false;
          }
        };
    flaky.insertWallet(wallets.findByCustomerId(customerId).orElseThrow());
    flaky.insertTransaction(
        new WalletTxRecord(
            Ids.newId(),
            flaky.findByCustomerId(customerId).orElseThrow().id(),
            WalletTxType.CREDIT,
            1_000L,
            1_000L,
            "REFUND",
            "open",
            null,
            null,
            null,
            NOW.plus(10, ChronoUnit.DAYS),
            1_000L,
            NOW));
    flaky.updateWallet(
        new WalletRecord(
            flaky.findByCustomerId(customerId).orElseThrow().id(),
            customerId,
            1_000L,
            1_000L,
            0,
            1,
            NOW,
            NOW),
        0);
    WalletService flakyService = new WalletService(flaky, profiles, rateLimiter, CLOCK, 100_000L);
    assertThatThrownBy(() -> flakyService.debitForOrder(customerId, Ids.newId(), 500L, "x"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INSUFFICIENT_WALLET_BALANCE");
  }

  @Test
  void adminCredit_missingIdempotencyKey_returns400() {
    assertThatThrownBy(
            () ->
                service.adminCredit(
                    financeAdmin,
                    customerId,
                    new WalletService.AdminCreditCommand(10, "REFUND", "n", null, null)))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(
            () ->
                service.adminCredit(
                    financeAdmin,
                    customerId,
                    new WalletService.AdminCreditCommand(10, "REFUND", "n", null, "   ")))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(
            () ->
                service.adminCredit(
                    financeAdmin,
                    customerId,
                    new WalletService.AdminCreditCommand(10, "REFUND", "n", null, "x".repeat(256))))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
  }

  @Test
  void expireCredits_lostClaim_skipsWithoutDoubleDebit() {
    UUID walletId = wallets.findByCustomerId(customerId).orElseThrow().id();
    wallets.updateWallet(new WalletRecord(walletId, customerId, 1_000L, 1_000L, 0, 1, NOW, NOW), 0);
    UUID creditId = Ids.newId();
    wallets.insertTransaction(
        new WalletTxRecord(
            creditId,
            walletId,
            WalletTxType.CREDIT,
            1_000L,
            1_000L,
            "GOODWILL",
            "race",
            null,
            null,
            null,
            NOW.minusSeconds(1),
            1_000L,
            NOW.minusSeconds(1)));
    // Simulate another worker claiming remaining first
    assertThat(wallets.updateCreditRemaining(creditId, 1_000L, 0L)).isTrue();
    assertThat(service.expireCredits()).isZero();
    assertThat(wallets.findById(walletId).orElseThrow().balancePaise()).isEqualTo(1_000L);
  }

  @Test
  void adminCredit_nullCommand_validates() {
    assertThatThrownBy(() -> service.adminCredit(financeAdmin, customerId, null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
  }

  @Test
  void debitForOrder_negativeTotal() {
    assertThatThrownBy(() -> service.debitForOrder(customerId, Ids.newId(), -1, "x"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
  }

  @Test
  void adminCredit_createsWalletWhenMissing() {
    wallets.clear();
    Map<String, Object> result =
        service.adminCredit(financeAdmin, customerId, credit(12, "REFUND", "create", null));
    // profile fixture wallet_balance_paise=12500 + 1200 credit
    assertThat(result.get("new_balance")).isEqualTo(new BigDecimal("137.00"));
  }

  @Test
  void consumeCreditsFifo_breaksWhenRemainingSatisfiedAcrossCredits() {
    service.adminCredit(financeAdmin, customerId, credit(20, "REFUND", "a", null));
    service.adminCredit(financeAdmin, customerId, credit(20, "REFUND", "b", null));
    Map<String, Object> result = service.debitForOrder(customerId, Ids.newId(), 2_000L, "pay");
    assertThat(result.get("amount_debited")).isEqualTo(new BigDecimal("20.00"));
  }

  @Test
  void parsePositiveAmountPaise_null() {
    assertThatThrownBy(() -> WalletService.parsePositiveAmountPaise(null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
  }

  @Test
  void coversRemainingBranches() {
    assertThat(service.listMyTransactions(customer, 1, 20, "  ", null, null).data()).isNotNull();

    assertThatThrownBy(
            () -> service.adminCredit(financeAdmin, customerId, credit(10, "REFUND", null, null)))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");

    assertThat(
            service
                .adminCredit(financeAdmin, customerId, credit(1, "REFUND", "n", "  "))
                .get("amount_credited"))
        .isEqualTo(new BigDecimal("1.00"));

    assertThatThrownBy(() -> service.adminCredit(null, customerId, credit(1, "REFUND", "n", null)))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("UNAUTHORIZED");

    MedmatePrincipal staff =
        new MedmatePrincipal(Ids.newId(), AuthRole.PHARMACY_STAFF, null, TokenScope.FULL, "j");
    assertThatThrownBy(() -> service.getMyWallet(staff))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("UNAUTHORIZED");

    service.adminCredit(financeAdmin, customerId, credit(3, "REFUND", "d", null));
    assertThat(service.debitForOrder(customerId, Ids.newId(), 50_000L, null).get("amount_debited"))
        .isNotNull();

    // null remaining credit skipped in fifo, then real credit debited
    UUID walletId = wallets.findByCustomerId(customerId).orElseThrow().id();
    wallets.insertTransaction(
        new WalletTxRecord(
            Ids.newId(),
            walletId,
            WalletTxType.CREDIT,
            50,
            50,
            "REFUND",
            "null-rem",
            null,
            null,
            null,
            NOW.plus(10, ChronoUnit.DAYS),
            null,
            NOW));
    service.adminCredit(financeAdmin, customerId, credit(4, "REFUND", "ok", null));
    assertThat(service.debitForOrder(customerId, Ids.newId(), 50_000L, "x").get("amount_debited"))
        .isEqualTo(new BigDecimal("4.00"));

    // expire when wallet balance lower than remaining (Math.max clamp)
    wallets.clear();
    profiles.saveProfile(CustomerTestFixtures.customer(customerId));
    UUID wid = Ids.newId();
    wallets.insertWallet(new WalletRecord(wid, customerId, 10L, 100L, 0, 0, NOW, NOW));
    UUID cid = Ids.newId();
    wallets.insertTransaction(
        new WalletTxRecord(
            cid,
            wid,
            WalletTxType.CREDIT,
            100L,
            100L,
            "GOODWILL",
            "over",
            null,
            null,
            null,
            NOW.minusSeconds(1),
            100L,
            NOW.minusSeconds(1)));
    assertThat(service.expireCredits()).isEqualTo(1);
    assertThat(wallets.findById(wid).orElseThrow().balancePaise()).isZero();
  }

  @Test
  void systemCredit_referral_isIdempotent() {
    Map<String, Object> first =
        service.systemCredit(
            customerId, 10_000L, "Referral reward", "ref-1", "referral-reward:evt1:referee");
    Map<String, Object> replay =
        service.systemCredit(
            customerId, 10_000L, "Referral reward", "ref-1", "referral-reward:evt1:referee");

    assertThat(first.get("amount_credited")).isEqualTo(new BigDecimal("100.00"));
    assertThat(first.get("reason")).isEqualTo("REFERRAL");
    assertThat(replay.get("transaction_id")).isEqualTo(first.get("transaction_id"));
    long balance = wallets.findByCustomerId(customerId).orElseThrow().balancePaise();
    assertThat(balance).isEqualTo(wallets.syncedBalance(customerId));
    assertThat(balance).isGreaterThanOrEqualTo(10_000L);
  }

  @Test
  void systemCredit_validationBranches() {
    assertThatThrownBy(() -> service.systemCredit(null, 100, "x", null, "k"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> service.systemCredit(customerId, 0, "x", null, "k"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_AMOUNT");
    assertThatThrownBy(() -> service.systemCredit(Ids.newId(), 100, "x", null, "k2"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("CUSTOMER_NOT_FOUND");
  }

  @Test
  void systemCredit_exceedsMaxCredit_returns422() {
    assertThatThrownBy(
            () -> service.systemCredit(customerId, 100_001L, "too big", null, "sys-over-cap"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("ADMIN_CREDIT_EXCEEDS_LIMIT");
  }

  @Test
  void systemCredit_lockedReplayAndNullDescription() {
    String idem = "sys-locked-replay";
    FakeWalletStore racing =
        new FakeWalletStore() {
          boolean first = true;

          @Override
          public Optional<WalletTxRecord> findByIdempotencyKey(String key) {
            if (!idem.equals(key)) {
              return Optional.empty();
            }
            if (first) {
              first = false;
              return Optional.empty();
            }
            return Optional.of(
                new WalletTxRecord(
                    Ids.newId(),
                    findByCustomerId(customerId).orElseThrow().id(),
                    WalletTxType.CREDIT,
                    100L,
                    100L,
                    "REFERRAL",
                    "replay",
                    null,
                    idem,
                    null,
                    NOW.plus(365, ChronoUnit.DAYS),
                    100L,
                    NOW));
          }
        };
    racing.insertWallet(wallets.findByCustomerId(customerId).orElseThrow());
    WalletService racingService = new WalletService(racing, profiles, rateLimiter, CLOCK, 100_000L);
    assertThat(racingService.systemCredit(customerId, 100L, null, null, idem).get("reason"))
        .isEqualTo("REFERRAL");
  }

  @Test
  void systemCredit_createsWalletAndBlankDescription() {
    wallets.clear();
    Map<String, Object> result =
        service.systemCredit(customerId, 1_000L, null, null, "sys-null-desc");
    assertThat(result.get("new_balance")).isEqualTo(new BigDecimal("135.00"));
    assertThat(wallets.allTransactions().getFirst().description())
        .isEqualTo("System wallet credit");

    service.systemCredit(customerId, 100L, "   ", null, "sys-blank-desc");
    assertThat(wallets.allTransactions().getLast().description()).isEqualTo("System wallet credit");
  }

  @Test
  void systemCredit_duplicateKeyRace_replaysExisting() {
    String idem = "sys-race-key";
    WalletTxRecord existing =
        new WalletTxRecord(
            Ids.newId(),
            wallets.findByCustomerId(customerId).orElseThrow().id(),
            WalletTxType.CREDIT,
            500L,
            500L,
            "REFERRAL",
            "won",
            null,
            idem,
            null,
            NOW.plus(365, ChronoUnit.DAYS),
            500L,
            NOW);
    FakeWalletStore racing =
        new FakeWalletStore() {
          @Override
          public WalletTxRecord insertTransaction(WalletTxRecord tx) {
            throw new org.springframework.dao.DuplicateKeyException("race");
          }

          @Override
          public Optional<WalletTxRecord> findByIdempotencyKey(String key) {
            return idem.equals(key) ? Optional.of(existing) : Optional.empty();
          }
        };
    racing.insertWallet(wallets.findByCustomerId(customerId).orElseThrow());
    WalletService racingService = new WalletService(racing, profiles, rateLimiter, CLOCK, 100_000L);
    assertThat(racingService.systemCredit(customerId, 500L, "x", null, idem).get("transaction_id"))
        .isEqualTo(existing.id());
  }

  @Test
  void systemCredit_duplicateKeyMissingRow_rethrows() {
    FakeWalletStore racing =
        new FakeWalletStore() {
          @Override
          public WalletTxRecord insertTransaction(WalletTxRecord tx) {
            throw new org.springframework.dao.DuplicateKeyException("race");
          }
        };
    racing.insertWallet(wallets.findByCustomerId(customerId).orElseThrow());
    WalletService racingService = new WalletService(racing, profiles, rateLimiter, CLOCK, 100_000L);
    assertThatThrownBy(() -> racingService.systemCredit(customerId, 100L, "x", null, "sys-miss"))
        .isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
  }

  @Test
  void debitStrict_ac001_idempotentReplay() {
    service.adminCredit(financeAdmin, customerId, credit(150, "REFUND", "seed", null));
    UUID orderId = Ids.newId();
    Map<String, Object> first =
        service.debitStrict(customerId, orderId, 5_000L, "checkout-1", "pay");
    assertThat(first.get("already_processed")).isEqualTo(false);
    assertThat(first.get("deducted_amount")).isEqualTo(new BigDecimal("50.00"));

    Map<String, Object> replay =
        service.debitStrict(customerId, orderId, 5_000L, "checkout-1", "pay");
    assertThat(replay.get("already_processed")).isEqualTo(true);
    assertThat(replay.get("transaction_id")).isEqualTo(first.get("transaction_id"));
    assertThat(wallets.findByCustomerId(customerId).orElseThrow().balancePaise())
        .isEqualTo(10_000L);
  }

  @Test
  void debitStrict_ac002_overDebitRejected() {
    service.adminCredit(financeAdmin, customerId, credit(150, "REFUND", "seed", null));
    assertThatThrownBy(() -> service.debitStrict(customerId, Ids.newId(), 20_000L, "over", "pay"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INSUFFICIENT_BALANCE");
    assertThat(wallets.findByCustomerId(customerId).orElseThrow().balancePaise())
        .isEqualTo(15_000L);
  }

  @Test
  void debitStrict_ac005_fifoConsumesOldestFirst() {
    UUID walletId = wallets.findByCustomerId(customerId).orElseThrow().id();
    Instant soon = NOW.plus(10, ChronoUnit.DAYS);
    Instant later = NOW.plus(300, ChronoUnit.DAYS);
    wallets.updateWallet(
        new WalletRecord(walletId, customerId, 15_000L, 15_000L, 0, 1, NOW, NOW), 0);
    UUID oldCredit = Ids.newId();
    UUID newCredit = Ids.newId();
    wallets.insertTransaction(
        new WalletTxRecord(
            oldCredit,
            walletId,
            WalletTxType.CREDIT,
            5_000L,
            5_000L,
            "REFUND",
            "old",
            null,
            null,
            null,
            soon,
            5_000L,
            NOW));
    wallets.insertTransaction(
        new WalletTxRecord(
            newCredit,
            walletId,
            WalletTxType.CREDIT,
            10_000L,
            15_000L,
            "REFUND",
            "new",
            null,
            null,
            null,
            later,
            10_000L,
            NOW));

    service.debitStrict(customerId, Ids.newId(), 7_500L, "fifo-1", "pay");

    assertThat(
            wallets.allTransactions().stream()
                .filter(t -> t.id().equals(oldCredit))
                .findFirst()
                .orElseThrow()
                .remainingPaise())
        .isZero();
    assertThat(
            wallets.allTransactions().stream()
                .filter(t -> t.id().equals(newCredit))
                .findFirst()
                .orElseThrow()
                .remainingPaise())
        .isEqualTo(7_500L);
  }

  @Test
  void getMyWalletBalance_ac004_expiringSoonWithin30Days() {
    UUID walletId = wallets.findByCustomerId(customerId).orElseThrow().id();
    wallets.updateWallet(new WalletRecord(walletId, customerId, 5_000L, 5_000L, 0, 1, NOW, NOW), 0);
    wallets.insertTransaction(
        new WalletTxRecord(
            Ids.newId(),
            walletId,
            WalletTxType.CREDIT,
            5_000L,
            5_000L,
            "REFUND",
            "soon",
            null,
            null,
            null,
            NOW.plus(10, ChronoUnit.DAYS),
            5_000L,
            NOW));

    Map<String, Object> view = service.getMyWalletBalance(customer);
    assertThat(view.get("customer_id")).isEqualTo(customerId);
    @SuppressWarnings("unchecked")
    Map<String, Object> soon = (Map<String, Object>) view.get("expiring_soon");
    assertThat(soon.get("amount")).isEqualTo(new BigDecimal("50.00"));
    assertThat(soon.get("expires_within_days")).isEqualTo(30);
  }

  @Test
  void listMyTransactions_ac007_includesBalanceBeforeAfter() {
    service.adminCredit(financeAdmin, customerId, credit(100, "REFUND", "r1", "ord-1"));
    WalletService.TxPage page = service.listMyTransactions(customer, 1, 20, null, null, null);
    assertThat(page.data().getFirst())
        .containsEntry("balance_before", new BigDecimal("0.00"))
        .containsEntry("balance_after", new BigDecimal("100.00"))
        .containsKey("transaction_id")
        .containsKey("note");
  }

  @Test
  void systemCredit_ac008_refundReason() {
    Map<String, Object> result =
        service.systemCredit(customerId, 2_500L, "COD refund", "order-1", "refund-1", "REFUND");
    assertThat(result.get("reason")).isEqualTo("REFUND");
    assertThat(result.get("amount")).isEqualTo(new BigDecimal("25.00"));
    assertThat(wallets.allTransactions().getFirst().reason()).isEqualTo("REFUND");
  }

  @Test
  void getBalanceAndListTransactionsForCustomer_portPaths() {
    service.adminCredit(financeAdmin, customerId, credit(40, "REFUND", "seed", null));
    Map<String, Object> bal = service.getBalanceForCustomer(customerId);
    assertThat(bal.get("customer_id")).isEqualTo(customerId);
    assertThat(bal.get("balance")).isEqualTo(new BigDecimal("40.00"));

    assertThatThrownBy(() -> service.getBalanceForCustomer(null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("CUSTOMER_NOT_FOUND");
    assertThatThrownBy(() -> service.getBalanceForCustomer(Ids.newId()))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("CUSTOMER_NOT_FOUND");

    WalletService.TxPage page = service.listTransactionsForCustomer(customerId, 1, 10, "CREDIT");
    assertThat(page.data()).hasSize(1);
    assertThatThrownBy(() -> service.listTransactionsForCustomer(null, 1, 10, null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("CUSTOMER_NOT_FOUND");
    assertThatThrownBy(() -> service.listTransactionsForCustomer(Ids.newId(), 1, 10, null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("CUSTOMER_NOT_FOUND");
  }

  @Test
  void debitStrict_validationAndEdgeBranches() {
    assertThatThrownBy(() -> service.debitStrict(null, Ids.newId(), 100, "k", "n"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("CUSTOMER_NOT_FOUND");
    assertThatThrownBy(() -> service.debitStrict(customerId, Ids.newId(), 0, "k", "n"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_AMOUNT");
    assertThatThrownBy(() -> service.debitStrict(Ids.newId(), Ids.newId(), 100, "k", "n"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("CUSTOMER_NOT_FOUND");

    service.adminCredit(financeAdmin, customerId, credit(50, "REFUND", "seed", null));
    Map<String, Object> debited = service.debitStrict(customerId, null, 1_000L, "blank-desc", null);
    assertThat(debited.get("already_processed")).isEqualTo(false);
    assertThat(debited.get("deducted_amount")).isEqualTo(new BigDecimal("10.00"));

    // create wallet via lock miss
    UUID fresh = Ids.newId();
    profiles.saveProfile(CustomerTestFixtures.customer(fresh));
    FakeWalletStore emptyLock =
        new FakeWalletStore() {
          @Override
          public Optional<WalletRecord> lockByCustomerId(UUID id) {
            return Optional.empty();
          }
        };
    WalletService createService =
        new WalletService(emptyLock, profiles, rateLimiter, CLOCK, 100_000L);
    assertThatThrownBy(() -> createService.debitStrict(fresh, Ids.newId(), 100, "cw", "n"))
        .extracting(ex -> ((AppException) ex).code())
        .isIn("INSUFFICIENT_BALANCE", "INSUFFICIENT_WALLET_BALANCE");
  }

  @Test
  void debitStrict_lockedReplayAndDuplicateKey() {
    String idem = "debit-locked-replay";
    WalletRecord wallet = wallets.findByCustomerId(customerId).orElseThrow();
    WalletTxRecord existing =
        new WalletTxRecord(
            Ids.newId(),
            wallet.id(),
            WalletTxType.DEBIT,
            100L,
            0L,
            "ORDER_PAYMENT",
            "pre",
            null,
            idem,
            null,
            null,
            null,
            NOW);
    FakeWalletStore racing =
        new FakeWalletStore() {
          private int finds;

          @Override
          public Optional<WalletTxRecord> findByIdempotencyKey(String key) {
            finds++;
            return finds == 1 ? Optional.empty() : Optional.of(existing);
          }
        };
    racing.insertWallet(wallet);
    WalletService racingService = new WalletService(racing, profiles, rateLimiter, CLOCK, 100_000L);
    Map<String, Object> replay =
        racingService.debitStrict(customerId, Ids.newId(), 100L, idem, "x");
    assertThat(replay.get("already_processed")).isEqualTo(true);

    FakeWalletStore flaky =
        new FakeWalletStore() {
          @Override
          public WalletTxRecord insertTransaction(WalletTxRecord tx) {
            if (tx.type() == WalletTxType.DEBIT) {
              super.insertTransaction(existing);
              throw new org.springframework.dao.DuplicateKeyException("race");
            }
            return super.insertTransaction(tx);
          }
        };
    flaky.insertWallet(wallet);
    flaky.insertTransaction(
        new WalletTxRecord(
            Ids.newId(),
            wallet.id(),
            WalletTxType.CREDIT,
            5_000L,
            5_000L,
            "REFUND",
            "open",
            null,
            null,
            null,
            NOW.plus(10, ChronoUnit.DAYS),
            5_000L,
            NOW));
    flaky.updateWallet(
        new WalletRecord(wallet.id(), customerId, 5_000L, 5_000L, 0, 1, NOW, NOW), 0);
    WalletService flakyService = new WalletService(flaky, profiles, rateLimiter, CLOCK, 100_000L);
    Map<String, Object> dup = flakyService.debitStrict(customerId, Ids.newId(), 100L, idem, "  ");
    assertThat(dup.get("already_processed")).isEqualTo(true);
  }

  @Test
  void listTransactions_includesExpiredBalanceBefore() {
    WalletRecord wallet = wallets.findByCustomerId(customerId).orElseThrow();
    wallets.updateWallet(new WalletRecord(wallet.id(), customerId, 100L, 100L, 0, 1, NOW, NOW), 0);
    wallets.insertTransaction(
        new WalletTxRecord(
            Ids.newId(),
            wallet.id(),
            WalletTxType.EXPIRED,
            100L,
            0L,
            "EXPIRY",
            "expired",
            Ids.newId().toString(),
            null,
            null,
            null,
            null,
            NOW));
    WalletService.TxPage page = service.listMyTransactions(customer, 1, 50, null, null, null);
    assertThat(page.data().getFirst())
        .containsEntry("type", "EXPIRED")
        .containsEntry("balance_before", new BigDecimal("1.00"));
  }

  @Test
  void systemCredit_duplicateKeyRace_replays() {
    String idem = "sys-dup";
    WalletRecord wallet = wallets.findByCustomerId(customerId).orElseThrow();
    WalletTxRecord existing =
        new WalletTxRecord(
            Ids.newId(),
            wallet.id(),
            WalletTxType.CREDIT,
            200L,
            200L,
            "REFUND",
            "raced",
            null,
            idem,
            null,
            NOW.plus(365, ChronoUnit.DAYS),
            200L,
            NOW);
    FakeWalletStore racing =
        new FakeWalletStore() {
          @Override
          public Optional<WalletTxRecord> findByIdempotencyKey(String key) {
            if (allTransactions().isEmpty()) {
              return Optional.empty();
            }
            return super.findByIdempotencyKey(key);
          }

          @Override
          public WalletTxRecord insertTransaction(WalletTxRecord tx) {
            super.insertTransaction(existing);
            throw new org.springframework.dao.DuplicateKeyException("race");
          }
        };
    racing.insertWallet(wallet);
    WalletService racingService = new WalletService(racing, profiles, rateLimiter, CLOCK, 100_000L);
    Map<String, Object> result =
        racingService.systemCredit(customerId, 200L, "n", null, idem, "REFUND");
    assertThat(result.get("transaction_id")).isEqualTo(existing.id());
    assertThat(result.get("already_processed")).isEqualTo(true);
  }
}
