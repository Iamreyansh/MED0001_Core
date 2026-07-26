package com.nammamedmate.customer.application.port.out;

import com.nammamedmate.customer.domain.WalletTxType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WalletStore {

  Optional<WalletRecord> findByCustomerId(UUID customerId);

  Optional<WalletRecord> findById(UUID walletId);

  /** Locks the wallet row (`SELECT … FOR UPDATE`) for the current transaction. */
  Optional<WalletRecord> lockByCustomerId(UUID customerId);

  Optional<WalletRecord> lockById(UUID walletId);

  WalletRecord insertWallet(WalletRecord wallet);

  WalletRecord updateWallet(WalletRecord wallet, long expectedVersion);

  void syncCustomerBalancePaise(UUID customerId, long balancePaise);

  WalletTxRecord insertTransaction(WalletTxRecord tx);

  /**
   * Compare-and-set remaining_paise on a CREDIT row. Returns true only when exactly one row was
   * updated (prevents double-expiry / concurrent consume).
   */
  boolean updateCreditRemaining(UUID creditTxId, long expectedRemaining, long remainingPaise);

  Optional<WalletTxRecord> findByIdempotencyKey(String idempotencyKey);

  List<WalletTxRecord> listTransactions(
      UUID walletId, WalletTxType type, String sort, String order, int limit, int offset);

  long countTransactions(UUID walletId, WalletTxType type);

  /**
   * Open CREDIT rows with remaining_paise &gt; 0 and expires_at &lt;= now, earliest first. Claims
   * rows with {@code FOR UPDATE SKIP LOCKED} so concurrent schedulers do not race.
   */
  List<WalletTxRecord> findExpiredOpenCredits(Instant now, int limit);

  /** Open CREDIT rows for a wallet ordered by expires_at ASC (FIFO for debit). */
  List<WalletTxRecord> findOpenCreditsFifo(UUID walletId);

  /** Sum of remaining_paise for credits that expire before the given instant. */
  long sumRemainingExpiringBefore(UUID walletId, Instant before);

  /** Earliest expires_at among open credits that expire before the given instant. */
  Optional<Instant> earliestExpiryBefore(UUID walletId, Instant before);

  record WalletRecord(
      UUID id,
      UUID customerId,
      long balancePaise,
      long lifetimeCreditedPaise,
      long lifetimeDebitedPaise,
      long version,
      Instant createdAt,
      Instant updatedAt) {}

  record WalletTxRecord(
      UUID id,
      UUID walletId,
      WalletTxType type,
      long amountPaise,
      long balanceAfterPaise,
      String reason,
      String description,
      String referenceId,
      String idempotencyKey,
      UUID creditedBy,
      Instant expiresAt,
      Long remainingPaise,
      Instant createdAt) {}
}
