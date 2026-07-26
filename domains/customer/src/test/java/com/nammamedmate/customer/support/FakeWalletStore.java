package com.nammamedmate.customer.support;

import com.nammamedmate.customer.application.port.out.WalletStore;
import com.nammamedmate.customer.domain.WalletTxType;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import org.springframework.dao.DuplicateKeyException;

public class FakeWalletStore implements WalletStore {

  private final Map<UUID, WalletRecord> byId = new ConcurrentHashMap<>();
  private final Map<UUID, UUID> customerToWallet = new ConcurrentHashMap<>();
  private final List<WalletTxRecord> transactions = new ArrayList<>();
  private final Map<UUID, Long> customerBalances = new ConcurrentHashMap<>();

  @Override
  public Optional<WalletRecord> findByCustomerId(UUID customerId) {
    UUID id = customerToWallet.get(customerId);
    return id == null ? Optional.empty() : Optional.ofNullable(byId.get(id));
  }

  @Override
  public Optional<WalletRecord> findById(UUID walletId) {
    return Optional.ofNullable(byId.get(walletId));
  }

  @Override
  public Optional<WalletRecord> lockByCustomerId(UUID customerId) {
    return findByCustomerId(customerId);
  }

  @Override
  public Optional<WalletRecord> lockById(UUID walletId) {
    return findById(walletId);
  }

  @Override
  public WalletRecord insertWallet(WalletRecord wallet) {
    if (customerToWallet.containsKey(wallet.customerId())) {
      throw new IllegalStateException("duplicate wallet");
    }
    byId.put(wallet.id(), wallet);
    customerToWallet.put(wallet.customerId(), wallet.id());
    return wallet;
  }

  public void clear() {
    byId.clear();
    customerToWallet.clear();
    transactions.clear();
    customerBalances.clear();
  }

  @Override
  public WalletRecord updateWallet(WalletRecord wallet, long expectedVersion) {
    WalletRecord current = byId.get(wallet.id());
    if (current == null || current.version() != expectedVersion) {
      throw new IllegalStateException("optimistic lock");
    }
    byId.put(wallet.id(), wallet);
    return wallet;
  }

  @Override
  public void syncCustomerBalancePaise(UUID customerId, long balancePaise) {
    customerBalances.put(customerId, balancePaise);
  }

  public Long syncedBalance(UUID customerId) {
    return customerBalances.get(customerId);
  }

  @Override
  public WalletTxRecord insertTransaction(WalletTxRecord tx) {
    if (tx.idempotencyKey() != null
        && transactions.stream().anyMatch(t -> tx.idempotencyKey().equals(t.idempotencyKey()))) {
      throw new DuplicateKeyException("duplicate idempotency_key");
    }
    if (tx.type() == WalletTxType.EXPIRED
        && tx.referenceId() != null
        && transactions.stream()
            .anyMatch(
                t ->
                    t.type() == WalletTxType.EXPIRED && tx.referenceId().equals(t.referenceId()))) {
      throw new DuplicateKeyException("duplicate expired credit");
    }
    transactions.add(tx);
    return tx;
  }

  @Override
  public boolean updateCreditRemaining(
      UUID creditTxId, long expectedRemaining, long remainingPaise) {
    for (int i = 0; i < transactions.size(); i++) {
      WalletTxRecord tx = transactions.get(i);
      if (tx.id().equals(creditTxId)
          && tx.type() == WalletTxType.CREDIT
          && tx.remainingPaise() != null
          && tx.remainingPaise() == expectedRemaining) {
        transactions.set(
            i,
            new WalletTxRecord(
                tx.id(),
                tx.walletId(),
                tx.type(),
                tx.amountPaise(),
                tx.balanceAfterPaise(),
                tx.reason(),
                tx.description(),
                tx.referenceId(),
                tx.idempotencyKey(),
                tx.creditedBy(),
                tx.expiresAt(),
                remainingPaise,
                tx.createdAt()));
        return true;
      }
    }
    return false;
  }

  @Override
  public Optional<WalletTxRecord> findByIdempotencyKey(String idempotencyKey) {
    return transactions.stream().filter(t -> idempotencyKey.equals(t.idempotencyKey())).findFirst();
  }

  @Override
  public List<WalletTxRecord> listTransactions(
      UUID walletId, WalletTxType type, String sort, String order, int limit, int offset) {
    Stream<WalletTxRecord> stream =
        transactions.stream().filter(t -> t.walletId().equals(walletId));
    if (type != null) {
      stream = stream.filter(t -> t.type() == type);
    }
    Comparator<WalletTxRecord> cmp = Comparator.comparing(WalletTxRecord::createdAt);
    if (!"asc".equalsIgnoreCase(order)) {
      cmp = cmp.reversed();
    }
    return stream.sorted(cmp).skip(offset).limit(limit).toList();
  }

  @Override
  public long countTransactions(UUID walletId, WalletTxType type) {
    return transactions.stream()
        .filter(t -> t.walletId().equals(walletId))
        .filter(t -> type == null || t.type() == type)
        .count();
  }

  @Override
  public List<WalletTxRecord> findExpiredOpenCredits(Instant now, int limit) {
    return transactions.stream()
        .filter(t -> t.type() == WalletTxType.CREDIT)
        .filter(t -> t.expiresAt() != null && !t.expiresAt().isAfter(now))
        .filter(t -> t.remainingPaise() == null || t.remainingPaise() > 0)
        .sorted(
            Comparator.comparing(WalletTxRecord::expiresAt)
                .thenComparing(WalletTxRecord::createdAt))
        .limit(limit)
        .toList();
  }

  @Override
  public List<WalletTxRecord> findOpenCreditsFifo(UUID walletId) {
    return transactions.stream()
        .filter(t -> t.walletId().equals(walletId))
        .filter(t -> t.type() == WalletTxType.CREDIT)
        .filter(t -> t.remainingPaise() == null || t.remainingPaise() > 0)
        .sorted(
            Comparator.comparing(
                    WalletTxRecord::expiresAt, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(WalletTxRecord::createdAt))
        .toList();
  }

  @Override
  public long sumRemainingExpiringBefore(UUID walletId, Instant before) {
    return transactions.stream()
        .filter(t -> t.walletId().equals(walletId))
        .filter(t -> t.type() == WalletTxType.CREDIT)
        .filter(t -> t.remainingPaise() != null && t.remainingPaise() > 0)
        .filter(t -> t.expiresAt() != null && t.expiresAt().isBefore(before))
        .mapToLong(WalletTxRecord::remainingPaise)
        .sum();
  }

  @Override
  public Optional<Instant> earliestExpiryBefore(UUID walletId, Instant before) {
    return transactions.stream()
        .filter(t -> t.walletId().equals(walletId))
        .filter(t -> t.type() == WalletTxType.CREDIT)
        .filter(t -> t.remainingPaise() != null && t.remainingPaise() > 0)
        .filter(t -> t.expiresAt() != null && t.expiresAt().isBefore(before))
        .map(WalletTxRecord::expiresAt)
        .min(Comparator.naturalOrder());
  }

  public List<WalletTxRecord> allTransactions() {
    return List.copyOf(transactions);
  }
}
