package com.nammamedmate.customer.support;

import com.nammamedmate.customer.application.port.out.LoyaltyStore;
import com.nammamedmate.customer.domain.LoyaltyTxType;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import org.springframework.dao.DuplicateKeyException;

public class FakeLoyaltyStore implements LoyaltyStore {

  private final Map<UUID, LoyaltyRecord> byCustomer = new ConcurrentHashMap<>();
  private final List<LoyaltyTxRecord> transactions = new ArrayList<>();
  private final Map<UUID, Integer> syncedPoints = new ConcurrentHashMap<>();
  public boolean failNextInsert;
  public boolean failNextInsertTx;
  public LoyaltyTxRecord revealAfterFailedInsert;
  public LoyaltyRecord revealAfterFailedLoyaltyInsert;

  @Override
  public Optional<LoyaltyRecord> findByCustomerId(UUID customerId) {
    return Optional.ofNullable(byCustomer.get(customerId));
  }

  @Override
  public Optional<LoyaltyRecord> lockByCustomerId(UUID customerId) {
    return findByCustomerId(customerId);
  }

  @Override
  public LoyaltyRecord insert(LoyaltyRecord record) {
    if (failNextInsert) {
      failNextInsert = false;
      if (revealAfterFailedLoyaltyInsert != null) {
        byCustomer.put(revealAfterFailedLoyaltyInsert.customerId(), revealAfterFailedLoyaltyInsert);
        revealAfterFailedLoyaltyInsert = null;
      }
      throw new DuplicateKeyException("duplicate loyalty");
    }
    if (byCustomer.containsKey(record.customerId())) {
      throw new DuplicateKeyException("duplicate loyalty");
    }
    byCustomer.put(record.customerId(), record);
    return record;
  }

  @Override
  public LoyaltyRecord update(LoyaltyRecord record) {
    byCustomer.put(record.customerId(), record);
    return record;
  }

  @Override
  public void syncCustomerLoyaltyPoints(UUID customerId, int pointsBalance) {
    syncedPoints.put(customerId, pointsBalance);
  }

  public Integer syncedPoints(UUID customerId) {
    return syncedPoints.get(customerId);
  }

  @Override
  public LoyaltyTxRecord insertTransaction(LoyaltyTxRecord tx) {
    if (failNextInsertTx) {
      failNextInsertTx = false;
      if (revealAfterFailedInsert != null) {
        transactions.add(revealAfterFailedInsert);
        revealAfterFailedInsert = null;
      }
      throw new DuplicateKeyException("duplicate loyalty tx");
    }
    if (tx.referenceId() != null
        && transactions.stream()
            .anyMatch(t -> t.type() == tx.type() && tx.referenceId().equals(t.referenceId()))) {
      throw new DuplicateKeyException("duplicate loyalty tx");
    }
    transactions.add(tx);
    return tx;
  }

  @Override
  public Optional<LoyaltyTxRecord> findByReferenceAndType(UUID referenceId, LoyaltyTxType type) {
    return transactions.stream()
        .filter(t -> t.type() == type && referenceId.equals(t.referenceId()))
        .findFirst();
  }

  @Override
  public List<LoyaltyTxRecord> listTransactions(
      UUID customerId, LoyaltyTxType type, String order, int limit, int offset) {
    boolean asc = "asc".equalsIgnoreCase(order);
    Stream<LoyaltyTxRecord> stream =
        transactions.stream().filter(t -> t.customerId().equals(customerId));
    if (type != null) {
      stream = stream.filter(t -> t.type() == type);
    }
    Comparator<LoyaltyTxRecord> cmp = Comparator.comparing(LoyaltyTxRecord::createdAt);
    if (!asc) {
      cmp = cmp.reversed();
    }
    return stream.sorted(cmp).skip(offset).limit(limit).toList();
  }

  @Override
  public long countTransactions(UUID customerId, LoyaltyTxType type) {
    return transactions.stream()
        .filter(t -> t.customerId().equals(customerId))
        .filter(t -> type == null || t.type() == type)
        .count();
  }

  public List<LoyaltyTxRecord> allTransactions() {
    return List.copyOf(transactions);
  }
}
