package com.nammamedmate.customer.application;

import com.nammamedmate.customer.application.port.out.CustomerProfileStore;
import com.nammamedmate.customer.application.port.out.WalletCreditLimitPort;
import com.nammamedmate.customer.application.port.out.WalletStore;
import com.nammamedmate.customer.application.port.out.WalletStore.WalletRecord;
import com.nammamedmate.customer.application.port.out.WalletStore.WalletTxRecord;
import com.nammamedmate.customer.domain.WalletCreditReason;
import com.nammamedmate.customer.domain.WalletTxType;
import com.nammamedmate.kernel.api.PageRequest;
import com.nammamedmate.kernel.api.PaginationMeta;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.kernel.ratelimit.RateLimiter;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WalletService {

  private static final int BALANCE_LIMIT = 30;
  private static final int TX_LIST_LIMIT = 20;
  private static final int CREDIT_LIMIT = 20;
  private static final int MINUTE = 60;
  private static final int CREDIT_TTL_DAYS = 365;
  private static final int EXPIRING_SOON_DAYS = 30;
  private static final int EXPIRY_BATCH = 200;
  private static final Set<String> TX_SORTS = Set.of("created_at");

  private final WalletStore wallets;
  private final CustomerProfileStore profiles;
  private final RateLimiter rateLimiter;
  private final Clock clock;
  private final WalletCreditLimitPort creditLimit;

  public WalletService(
      WalletStore wallets,
      CustomerProfileStore profiles,
      RateLimiter rateLimiter,
      Clock clock,
      WalletCreditLimitPort creditLimit) {
    this.wallets = wallets;
    this.profiles = profiles;
    this.rateLimiter = rateLimiter;
    this.clock = clock;
    this.creditLimit = creditLimit;
  }

  private long maxCreditPaise() {
    return creditLimit.maxCreditPaise();
  }

  @Transactional(readOnly = true)
  public Map<String, Object> getMyWallet(MedmatePrincipal principal) {
    UUID customerId = requireCustomerId(principal);
    rateLimit("customer:wallet:get:" + customerId, BALANCE_LIMIT, MINUTE);
    WalletRecord wallet = requireWallet(customerId);
    return toBalanceView(wallet);
  }

  /** EPIC-012 story shape for {@code GET /customers/me/wallet/balance}. */
  @Transactional(readOnly = true)
  public Map<String, Object> getMyWalletBalance(MedmatePrincipal principal) {
    UUID customerId = requireCustomerId(principal);
    rateLimit("customer:wallet:balance:" + customerId, BALANCE_LIMIT, MINUTE);
    return toStoryBalanceView(customerId, requireWallet(customerId));
  }

  @Transactional(readOnly = true)
  public Map<String, Object> getBalanceForCustomer(UUID customerId) {
    if (customerId == null || profiles.findById(customerId).isEmpty()) {
      throw new AppException("CUSTOMER_NOT_FOUND", "Customer not found", 404);
    }
    return toStoryBalanceView(customerId, requireWallet(customerId));
  }

  @Transactional(readOnly = true)
  public TxPage listMyTransactions(
      MedmatePrincipal principal,
      Integer page,
      Integer limit,
      String sort,
      String order,
      String type) {
    UUID customerId = requireCustomerId(principal);
    rateLimit("customer:wallet:tx:" + customerId, TX_LIST_LIMIT, MINUTE);
    WalletRecord wallet = requireWallet(customerId);

    String sortField = (sort == null || sort.isBlank()) ? "created_at" : sort.trim();
    if (!TX_SORTS.contains(sortField)) {
      throw new AppException("VALIDATION_ERROR", "sort must be one of: created_at", 400);
    }
    // Story default is desc (newest first); PageRequest.normalize coerces non-desc to asc.
    String effectiveOrder = (order == null || order.isBlank()) ? "desc" : order;
    PageRequest pageReq = PageRequest.normalize(page, limit, sortField, effectiveOrder);
    WalletTxType filter = WalletTxType.parseOptional(type);

    List<Map<String, Object>> items =
        wallets
            .listTransactions(
                wallet.id(),
                filter,
                pageReq.sort(),
                pageReq.order(),
                pageReq.limit(),
                pageReq.offset())
            .stream()
            .map(WalletService::toTxView)
            .toList();
    long total = wallets.countTransactions(wallet.id(), filter);
    return new TxPage(items, PaginationMeta.of(pageReq.page(), pageReq.limit(), total));
  }

  @Transactional(readOnly = true)
  public TxPage listTransactionsForCustomer(
      UUID customerId, Integer page, Integer limit, String type) {
    if (customerId == null || profiles.findById(customerId).isEmpty()) {
      throw new AppException("CUSTOMER_NOT_FOUND", "Customer not found", 404);
    }
    WalletRecord wallet = requireWallet(customerId);
    PageRequest pageReq = PageRequest.normalize(page, limit, "created_at", "desc");
    WalletTxType filter = WalletTxType.parseOptional(type);
    List<Map<String, Object>> items =
        wallets
            .listTransactions(
                wallet.id(),
                filter,
                pageReq.sort(),
                pageReq.order(),
                pageReq.limit(),
                pageReq.offset())
            .stream()
            .map(WalletService::toTxView)
            .toList();
    long total = wallets.countTransactions(wallet.id(), filter);
    return new TxPage(items, PaginationMeta.of(pageReq.page(), pageReq.limit(), total));
  }

  @Transactional
  public Map<String, Object> adminCredit(
      MedmatePrincipal principal, UUID customerId, AdminCreditCommand cmd) {
    requireAdmin(principal);
    rateLimit("admin:wallet:credit:" + principal.subject(), CREDIT_LIMIT, MINUTE);

    if (profiles.findById(customerId).isEmpty()) {
      throw new AppException("CUSTOMER_NOT_FOUND", "Customer not found", 404);
    }
    if (cmd == null) {
      throw new AppException("VALIDATION_ERROR", "request body is required", 400);
    }

    String idempotencyKey = requireIdempotencyKey(cmd.idempotencyKey());
    Optional<WalletTxRecord> replay = wallets.findByIdempotencyKey(idempotencyKey);
    if (replay.isPresent()) {
      return toCreditView(customerId, replay.get(), true);
    }

    long amountPaise = parsePositiveAmountPaise(cmd.amount());
    if (amountPaise > maxCreditPaise()) {
      throw new AppException(
          "ADMIN_CREDIT_EXCEEDS_LIMIT", "Amount exceeds max_wallet_credit_per_transaction", 422);
    }
    WalletCreditReason reason = WalletCreditReason.require(cmd.reason());
    String note = requireNote(cmd.note());
    String referenceId = trimToNull(cmd.referenceId(), 255);

    Instant now = clock.instant();
    WalletRecord locked =
        wallets.lockByCustomerId(customerId).orElseGet(() -> createWallet(customerId, now));

    // Re-check under wallet lock (same-wallet serialization); cross-wallet races hit unique index.
    Optional<WalletTxRecord> lockedReplay = wallets.findByIdempotencyKey(idempotencyKey);
    if (lockedReplay.isPresent()) {
      return toCreditView(customerId, lockedReplay.get(), true);
    }

    long newBalance = locked.balancePaise() + amountPaise;
    Instant expiresAt = now.plus(CREDIT_TTL_DAYS, ChronoUnit.DAYS);
    UUID txId = Ids.newId();

    WalletTxRecord credit =
        new WalletTxRecord(
            txId,
            locked.id(),
            WalletTxType.CREDIT,
            amountPaise,
            newBalance,
            reason.name(),
            note,
            referenceId,
            idempotencyKey,
            principal.subject(),
            expiresAt,
            amountPaise,
            now);
    try {
      // Insert before balance bump so a lost unique-index race never mutates the wallet.
      wallets.insertTransaction(credit);
    } catch (DuplicateKeyException ex) {
      return wallets
          .findByIdempotencyKey(idempotencyKey)
          .map(tx -> toCreditView(customerId, tx, true))
          .orElseThrow(() -> ex);
    }

    WalletRecord updated =
        new WalletRecord(
            locked.id(),
            locked.customerId(),
            newBalance,
            locked.lifetimeCreditedPaise() + amountPaise,
            locked.lifetimeDebitedPaise(),
            locked.version() + 1,
            locked.createdAt(),
            now);
    wallets.updateWallet(updated, locked.version());
    wallets.syncCustomerBalancePaise(customerId, newBalance);

    return toCreditView(customerId, credit, false);
  }

  /**
   * System credit (referral rewards, refunds, etc.). Idempotent via idempotencyKey. Not exposed on
   * admin credit API — reason may be {@link WalletCreditReason#REFERRAL}.
   */
  @Transactional
  public Map<String, Object> systemCredit(
      UUID customerId,
      long amountPaise,
      String description,
      String referenceId,
      String idempotencyKey) {
    return systemCredit(
        customerId,
        amountPaise,
        description,
        referenceId,
        idempotencyKey,
        WalletCreditReason.REFERRAL.name());
  }

  @Transactional
  public Map<String, Object> systemCredit(
      UUID customerId,
      long amountPaise,
      String description,
      String referenceId,
      String idempotencyKey,
      String reason) {
    if (customerId == null) {
      throw new AppException("VALIDATION_ERROR", "customer_id is required", 400);
    }
    if (amountPaise <= 0) {
      throw new AppException("INVALID_AMOUNT", "amount must be positive", 422);
    }
    if (amountPaise > maxCreditPaise()) {
      throw new AppException(
          "ADMIN_CREDIT_EXCEEDS_LIMIT", "Amount exceeds max_wallet_credit_per_transaction", 422);
    }
    WalletCreditReason creditReason = WalletCreditReason.requireSystem(reason);
    String key = requireIdempotencyKey(idempotencyKey);
    Optional<WalletTxRecord> replay = wallets.findByIdempotencyKey(key);
    if (replay.isPresent()) {
      return toCreditView(customerId, replay.get(), true);
    }
    if (profiles.findById(customerId).isEmpty()) {
      throw new AppException("CUSTOMER_NOT_FOUND", "Customer not found", 404);
    }

    Instant now = clock.instant();
    WalletRecord locked =
        wallets.lockByCustomerId(customerId).orElseGet(() -> createWallet(customerId, now));

    Optional<WalletTxRecord> lockedReplay = wallets.findByIdempotencyKey(key);
    if (lockedReplay.isPresent()) {
      return toCreditView(customerId, lockedReplay.get(), true);
    }

    long newBalance = locked.balancePaise() + amountPaise;
    Instant expiresAt = now.plus(CREDIT_TTL_DAYS, ChronoUnit.DAYS);
    UUID txId = Ids.newId();
    String desc =
        description == null || description.isBlank()
            ? "System wallet credit"
            : truncate(description, 500);
    String ref = trimToNull(referenceId, 255);

    WalletTxRecord credit =
        new WalletTxRecord(
            txId,
            locked.id(),
            WalletTxType.CREDIT,
            amountPaise,
            newBalance,
            creditReason.name(),
            desc,
            ref,
            key,
            null,
            expiresAt,
            amountPaise,
            now);
    try {
      wallets.insertTransaction(credit);
    } catch (DuplicateKeyException ex) {
      return wallets
          .findByIdempotencyKey(key)
          .map(tx -> toCreditView(customerId, tx, true))
          .orElseThrow(() -> ex);
    }

    WalletRecord updated =
        new WalletRecord(
            locked.id(),
            locked.customerId(),
            newBalance,
            locked.lifetimeCreditedPaise() + amountPaise,
            locked.lifetimeDebitedPaise(),
            locked.version() + 1,
            locked.createdAt(),
            now);
    wallets.updateWallet(updated, locked.version());
    wallets.syncCustomerBalancePaise(customerId, newBalance);
    return toCreditView(customerId, credit, false);
  }

  /**
   * Checkout debit: apply entire wallet balance up to order total. Used by order domain later
   * (EPIC-010); unit-tested here for STORY-003 AC.
   */
  @Transactional
  public Map<String, Object> debitForOrder(
      UUID customerId, UUID orderId, long orderTotalPaise, String description) {
    if (orderTotalPaise <= 0) {
      throw new AppException("VALIDATION_ERROR", "order total must be positive", 400);
    }
    Instant now = clock.instant();
    WalletRecord locked =
        wallets
            .lockByCustomerId(customerId)
            .orElseThrow(() -> new AppException("CUSTOMER_NOT_FOUND", "Customer not found", 404));

    long debitPaise = Math.min(locked.balancePaise(), orderTotalPaise);
    if (debitPaise <= 0) {
      Map<String, Object> empty = new LinkedHashMap<>();
      empty.put("amount_debited", paiseToRupees(0));
      empty.put("new_balance", paiseToRupees(locked.balancePaise()));
      empty.put("transaction_id", null);
      return empty;
    }

    consumeCreditsFifo(locked.id(), debitPaise);

    long newBalance = locked.balancePaise() - debitPaise;
    UUID txId = Ids.newId();
    WalletRecord updated =
        new WalletRecord(
            locked.id(),
            locked.customerId(),
            newBalance,
            locked.lifetimeCreditedPaise(),
            locked.lifetimeDebitedPaise() + debitPaise,
            locked.version() + 1,
            locked.createdAt(),
            now);
    wallets.updateWallet(updated, locked.version());
    wallets.syncCustomerBalancePaise(customerId, newBalance);

    String desc =
        description == null || description.isBlank()
            ? "Payment for order"
            : truncate(description, 500);
    wallets.insertTransaction(
        new WalletTxRecord(
            txId,
            locked.id(),
            WalletTxType.DEBIT,
            debitPaise,
            newBalance,
            "ORDER_PAYMENT",
            desc,
            orderId == null ? null : orderId.toString(),
            null,
            null,
            null,
            null,
            now));

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("transaction_id", txId);
    data.put("amount_debited", paiseToRupees(debitPaise));
    data.put("new_balance", paiseToRupees(newBalance));
    return data;
  }

  /**
   * Strict checkout debit (EPIC-012): rejects when amount exceeds balance; idempotent per key.
   * Unlike {@link #debitForOrder}, does not cap to available balance.
   */
  @Transactional
  public Map<String, Object> debitStrict(
      UUID customerId, UUID orderId, long amountPaise, String idempotencyKey, String description) {
    if (customerId == null) {
      throw new AppException("CUSTOMER_NOT_FOUND", "Customer not found", 404);
    }
    if (amountPaise <= 0) {
      throw new AppException("INVALID_AMOUNT", "amount must be > 0", 422);
    }
    String key = requireIdempotencyKey(idempotencyKey);
    Optional<WalletTxRecord> replay = wallets.findByIdempotencyKey(key);
    if (replay.isPresent()) {
      return toDebitView(customerId, replay.get(), key, true);
    }
    if (profiles.findById(customerId).isEmpty()) {
      throw new AppException("CUSTOMER_NOT_FOUND", "Customer not found", 404);
    }

    Instant now = clock.instant();
    WalletRecord locked =
        wallets.lockByCustomerId(customerId).orElseGet(() -> createWallet(customerId, now));

    Optional<WalletTxRecord> lockedReplay = wallets.findByIdempotencyKey(key);
    if (lockedReplay.isPresent()) {
      return toDebitView(customerId, lockedReplay.get(), key, true);
    }

    if (amountPaise > locked.balancePaise()) {
      throw new AppException("INSUFFICIENT_BALANCE", "Wallet balance insufficient for debit", 422);
    }

    consumeCreditsFifo(locked.id(), amountPaise);

    long balanceBefore = locked.balancePaise();
    long newBalance = balanceBefore - amountPaise;
    UUID txId = Ids.newId();
    WalletRecord updated =
        new WalletRecord(
            locked.id(),
            locked.customerId(),
            newBalance,
            locked.lifetimeCreditedPaise(),
            locked.lifetimeDebitedPaise() + amountPaise,
            locked.version() + 1,
            locked.createdAt(),
            now);
    wallets.updateWallet(updated, locked.version());
    wallets.syncCustomerBalancePaise(customerId, newBalance);

    String desc =
        description == null || description.isBlank()
            ? "Auto-applied at checkout"
            : truncate(description, 500);
    WalletTxRecord debit =
        new WalletTxRecord(
            txId,
            locked.id(),
            WalletTxType.DEBIT,
            amountPaise,
            newBalance,
            "ORDER_PAYMENT",
            desc,
            orderId == null ? null : orderId.toString(),
            key,
            null,
            null,
            null,
            now);
    try {
      wallets.insertTransaction(debit);
    } catch (DuplicateKeyException ex) {
      return wallets
          .findByIdempotencyKey(key)
          .map(tx -> toDebitView(customerId, tx, key, true))
          .orElseThrow(() -> ex);
    }

    return toDebitView(customerId, debit, key, false);
  }

  /** Nightly job: mark expired open credits and decrement wallet balance. */
  @Transactional
  public int expireCredits() {
    Instant now = clock.instant();
    int expiredCount = 0;
    while (true) {
      List<WalletTxRecord> batch = wallets.findExpiredOpenCredits(now, EXPIRY_BATCH);
      if (batch.isEmpty()) {
        break;
      }
      int before = expiredCount;
      for (WalletTxRecord credit : batch) {
        if (expireOneCredit(credit, now)) {
          expiredCount++;
        }
      }
      if (expiredCount == before) {
        break; // nothing progressed — avoid tight loop
      }
    }
    return expiredCount;
  }

  @Transactional(readOnly = true)
  public Map<String, Object> adminWalletSummary(UUID customerId) {
    return wallets
        .findByCustomerId(customerId)
        .map(
            w -> {
              Map<String, Object> wallet = new LinkedHashMap<>();
              wallet.put("balance", paiseToRupees(w.balancePaise()));
              wallet.put("lifetime_credited", paiseToRupees(w.lifetimeCreditedPaise()));
              wallet.put("lifetime_debited", paiseToRupees(w.lifetimeDebitedPaise()));
              return wallet;
            })
        .orElseGet(
            () -> {
              long balance =
                  profiles.findById(customerId).map(p -> p.walletBalancePaise()).orElse(0L);
              Map<String, Object> wallet = new LinkedHashMap<>();
              wallet.put("balance", paiseToRupees(balance));
              wallet.put("lifetime_credited", BigDecimal.ZERO.setScale(2));
              wallet.put("lifetime_debited", BigDecimal.ZERO.setScale(2));
              return wallet;
            });
  }

  private boolean expireOneCredit(WalletTxRecord credit, Instant now) {
    long remaining = credit.remainingPaise() == null ? 0L : credit.remainingPaise();
    if (remaining <= 0) {
      return false;
    }
    WalletRecord locked = wallets.lockById(credit.walletId()).orElse(null);
    if (locked == null) {
      return false;
    }
    // Claim remaining before debiting the wallet — lost CAS means another worker already expired.
    if (!wallets.updateCreditRemaining(credit.id(), remaining, 0L)) {
      return false;
    }

    long newBalance = Math.max(0L, locked.balancePaise() - remaining);

    WalletRecord updated =
        new WalletRecord(
            locked.id(),
            locked.customerId(),
            newBalance,
            locked.lifetimeCreditedPaise(),
            locked.lifetimeDebitedPaise(),
            locked.version() + 1,
            locked.createdAt(),
            now);
    wallets.updateWallet(updated, locked.version());
    wallets.syncCustomerBalancePaise(locked.customerId(), newBalance);

    wallets.insertTransaction(
        new WalletTxRecord(
            Ids.newId(),
            locked.id(),
            WalletTxType.EXPIRED,
            remaining,
            newBalance,
            "EXPIRY",
            "Credit expired",
            credit.id().toString(),
            null,
            null,
            null,
            null,
            now));
    return true;
  }

  private void consumeCreditsFifo(UUID walletId, long debitPaise) {
    long remaining = debitPaise;
    for (WalletTxRecord credit : wallets.findOpenCreditsFifo(walletId)) {
      if (remaining <= 0) {
        break;
      }
      long creditLeft = credit.remainingPaise() == null ? 0L : credit.remainingPaise();
      if (creditLeft <= 0) {
        continue;
      }
      long take = Math.min(creditLeft, remaining);
      if (!wallets.updateCreditRemaining(credit.id(), creditLeft, creditLeft - take)) {
        throw new AppException(
            "INSUFFICIENT_WALLET_BALANCE", "Wallet balance insufficient for debit", 422);
      }
      remaining -= take;
    }
    if (remaining > 0) {
      throw new AppException(
          "INSUFFICIENT_WALLET_BALANCE", "Wallet balance insufficient for debit", 422);
    }
  }

  private Map<String, Object> toBalanceView(WalletRecord wallet) {
    Instant soon = clock.instant().plus(EXPIRING_SOON_DAYS, ChronoUnit.DAYS);
    long expiringAmount = wallets.sumRemainingExpiringBefore(wallet.id(), soon);
    Instant expiresBefore = wallets.earliestExpiryBefore(wallet.id(), soon).orElse(null);

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("wallet_id", wallet.id());
    data.put("balance", paiseToRupees(wallet.balancePaise()));
    data.put("lifetime_credited", paiseToRupees(wallet.lifetimeCreditedPaise()));
    data.put("lifetime_debited", paiseToRupees(wallet.lifetimeDebitedPaise()));
    Map<String, Object> expiringSoon = new LinkedHashMap<>();
    expiringSoon.put("amount", paiseToRupees(expiringAmount));
    expiringSoon.put("expires_before", expiresBefore);
    expiringSoon.put("expires_within_days", EXPIRING_SOON_DAYS);
    data.put("expiring_soon", expiringSoon);
    data.put("currency", "INR");
    return data;
  }

  private Map<String, Object> toStoryBalanceView(UUID customerId, WalletRecord wallet) {
    Instant soon = clock.instant().plus(EXPIRING_SOON_DAYS, ChronoUnit.DAYS);
    long expiringAmount = wallets.sumRemainingExpiringBefore(wallet.id(), soon);
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("customer_id", customerId);
    data.put("balance", paiseToRupees(wallet.balancePaise()));
    Map<String, Object> expiringSoon = new LinkedHashMap<>();
    expiringSoon.put("amount", paiseToRupees(expiringAmount));
    expiringSoon.put("expires_within_days", EXPIRING_SOON_DAYS);
    data.put("expiring_soon", expiringSoon);
    return data;
  }

  private static Map<String, Object> toTxView(WalletTxRecord tx) {
    long balanceBefore = balanceBeforePaise(tx);
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("id", tx.id());
    data.put("transaction_id", tx.id());
    data.put("type", tx.type().name());
    data.put("amount", paiseToRupees(tx.amountPaise()));
    data.put("balance_before", paiseToRupees(balanceBefore));
    data.put("balance_after", paiseToRupees(tx.balanceAfterPaise()));
    data.put("reason", tx.reason());
    data.put("description", tx.description());
    data.put("note", tx.description());
    data.put("reference_id", tx.referenceId());
    data.put("expires_at", tx.expiresAt());
    data.put("created_at", tx.createdAt());
    return data;
  }

  private static Map<String, Object> toCreditView(
      UUID customerId, WalletTxRecord tx, boolean alreadyProcessed) {
    long balanceBefore = tx.balanceAfterPaise() - tx.amountPaise();
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("transaction_id", tx.id());
    data.put("customer_id", customerId);
    data.put("amount_credited", paiseToRupees(tx.amountPaise()));
    data.put("amount", paiseToRupees(tx.amountPaise()));
    data.put("balance_before", paiseToRupees(balanceBefore));
    data.put("new_balance", paiseToRupees(tx.balanceAfterPaise()));
    data.put("reason", tx.reason());
    data.put("note", tx.description());
    data.put("reference_id", tx.referenceId());
    data.put("expires_at", tx.expiresAt());
    data.put("credited_by", tx.creditedBy());
    data.put("created_at", tx.createdAt());
    data.put("already_processed", alreadyProcessed);
    return data;
  }

  private static Map<String, Object> toDebitView(
      UUID customerId, WalletTxRecord tx, String idempotencyKey, boolean alreadyProcessed) {
    long balanceBefore = tx.balanceAfterPaise() + tx.amountPaise();
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("transaction_id", tx.id());
    data.put("customer_id", customerId);
    data.put("deducted_amount", paiseToRupees(tx.amountPaise()));
    data.put("balance_before", paiseToRupees(balanceBefore));
    data.put("remaining_balance", paiseToRupees(tx.balanceAfterPaise()));
    data.put("idempotency_key", idempotencyKey);
    data.put("already_processed", alreadyProcessed);
    return data;
  }

  private static long balanceBeforePaise(WalletTxRecord tx) {
    return switch (tx.type()) {
      case CREDIT -> tx.balanceAfterPaise() - tx.amountPaise();
      case DEBIT, EXPIRED -> tx.balanceAfterPaise() + tx.amountPaise();
    };
  }

  private WalletRecord requireWallet(UUID customerId) {
    return wallets
        .findByCustomerId(customerId)
        .orElseGet(() -> createWallet(customerId, clock.instant()));
  }

  private WalletRecord createWallet(UUID customerId, Instant now) {
    long balance = profiles.findById(customerId).map(p -> p.walletBalancePaise()).orElse(0L);
    WalletRecord created = new WalletRecord(Ids.newId(), customerId, balance, 0L, 0L, 0L, now, now);
    try {
      return wallets.insertWallet(created);
    } catch (DuplicateKeyException | IllegalStateException ex) {
      return wallets
          .lockByCustomerId(customerId)
          .or(() -> wallets.findByCustomerId(customerId))
          .orElseThrow(() -> new AppException("CUSTOMER_NOT_FOUND", "Customer not found", 404));
    }
  }

  private UUID requireCustomerId(MedmatePrincipal principal) {
    if (principal == null || principal.role() != AuthRole.CUSTOMER) {
      throw new AppException("UNAUTHORIZED", "Customer authentication required", 401);
    }
    return principal.subject();
  }

  private static void requireAdmin(MedmatePrincipal principal) {
    if (principal == null || !principal.role().value().startsWith("admin_")) {
      throw new AppException("UNAUTHORIZED", "Admin authentication required", 401);
    }
  }

  private void rateLimit(String key, int limit, int windowSeconds) {
    if (!rateLimiter.tryAcquire(key, limit, windowSeconds)) {
      throw new AppException("RATE_LIMITED", "Too many requests", 429);
    }
  }

  static long parsePositiveAmountPaise(Object amount) {
    if (amount == null) {
      throw new AppException("VALIDATION_ERROR", "amount is required", 400);
    }
    BigDecimal value;
    if (amount instanceof BigDecimal bd) {
      value = bd;
    } else if (amount instanceof Number n) {
      value = BigDecimal.valueOf(n.doubleValue());
    } else if (amount instanceof String s) {
      try {
        value = new BigDecimal(s.trim());
      } catch (NumberFormatException ex) {
        throw new AppException("VALIDATION_ERROR", "amount must be a positive number", 400);
      }
    } else {
      throw new AppException("VALIDATION_ERROR", "amount must be a positive number", 400);
    }
    if (value.scale() > 2) {
      throw new AppException("VALIDATION_ERROR", "amount may have at most 2 decimal places", 400);
    }
    if (value.compareTo(BigDecimal.ZERO) <= 0) {
      throw new AppException("VALIDATION_ERROR", "amount must be positive", 400);
    }
    return value.movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact();
  }

  private static String requireNote(String note) {
    if (note == null || note.isBlank()) {
      throw new AppException("VALIDATION_ERROR", "note is required", 400);
    }
    String trimmed = note.trim();
    if (trimmed.length() > 500) {
      throw new AppException("VALIDATION_ERROR", "note must be at most 500 characters", 400);
    }
    return trimmed;
  }

  private static String requireIdempotencyKey(String key) {
    if (key == null || key.isBlank()) {
      throw new AppException("VALIDATION_ERROR", "Idempotency-Key is required", 400);
    }
    String trimmed = key.trim();
    if (trimmed.length() > 255) {
      throw new AppException(
          "VALIDATION_ERROR", "Idempotency-Key must be at most 255 characters", 400);
    }
    return trimmed;
  }

  private static String trimToNull(String value, int max) {
    if (value == null || value.isBlank()) {
      return null;
    }
    String trimmed = value.trim();
    if (trimmed.length() > max) {
      throw new AppException(
          "VALIDATION_ERROR", "reference_id must be at most " + max + " characters", 400);
    }
    return trimmed;
  }

  private static String truncate(String value, int max) {
    return value.length() <= max ? value : value.substring(0, max);
  }

  public static BigDecimal paiseToRupees(long paise) {
    return BigDecimal.valueOf(paise).movePointLeft(2);
  }

  public record AdminCreditCommand(
      Object amount, String reason, String note, String referenceId, String idempotencyKey) {}

  public record TxPage(List<Map<String, Object>> data, PaginationMeta meta) {
    public TxPage {
      data = List.copyOf(data);
    }
  }
}
