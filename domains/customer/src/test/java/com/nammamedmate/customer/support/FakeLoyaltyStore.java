package com.nammamedmate.customer.support;

import com.nammamedmate.customer.application.port.out.LoyaltyStore;
import com.nammamedmate.customer.domain.LoyaltyTxType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
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
  private ProgramSettingsRecord settings =
      new ProgramSettingsRecord(
          PROGRAM_SETTINGS_ID,
          100,
          BigDecimal.ONE.setScale(2),
          12,
          50,
          120,
          20,
          10,
          365,
          null,
          Instant.parse("2026-07-24T00:00:00Z"));
  public boolean failNextInsert;
  public boolean failNextInsertTx;
  public LoyaltyTxRecord revealAfterFailedInsert;
  public LoyaltyRecord revealAfterFailedLoyaltyInsert;
  public boolean failSettings;

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

  @Override
  public ProgramSettingsRecord getProgramSettings() {
    if (failSettings) {
      throw new IllegalStateException("missing settings");
    }
    return settings;
  }

  @Override
  public ProgramSettingsRecord updateProgramSettings(ProgramSettingsRecord next) {
    settings = next;
    return settings;
  }

  @Override
  public List<LoyaltyTxRecord> findOpenEarnBatchesFifo(UUID customerId) {
    return transactions.stream()
        .filter(t -> t.customerId().equals(customerId))
        .filter(t -> t.type() == LoyaltyTxType.EARN)
        .filter(t -> t.remainingPoints() == null || t.remainingPoints() > 0)
        .sorted(Comparator.comparing(LoyaltyTxRecord::createdAt).thenComparing(LoyaltyTxRecord::id))
        .toList();
  }

  @Override
  public void updateEarnRemaining(UUID txId, int remainingPoints) {
    for (int i = 0; i < transactions.size(); i++) {
      LoyaltyTxRecord t = transactions.get(i);
      if (t.id().equals(txId) && t.type() == LoyaltyTxType.EARN) {
        transactions.set(
            i,
            new LoyaltyTxRecord(
                t.id(),
                t.customerId(),
                t.type(),
                t.points(),
                t.pointsBalanceAfter(),
                t.description(),
                t.referenceId(),
                t.createdAt(),
                t.expiresAt(),
                remainingPoints,
                t.adjustedBy()));
        return;
      }
    }
  }

  @Override
  public List<LoyaltyTxRecord> findExpiredEarnBatches(Instant now, int limit) {
    return transactions.stream()
        .filter(t -> t.type() == LoyaltyTxType.EARN)
        .filter(t -> t.remainingPoints() == null || t.remainingPoints() > 0)
        .filter(t -> t.expiresAt() != null && !t.expiresAt().isAfter(now))
        .sorted(
            Comparator.comparing(LoyaltyTxRecord::expiresAt)
                .thenComparing(LoyaltyTxRecord::createdAt))
        .limit(limit)
        .toList();
  }

  @Override
  public OverviewStats overviewStats(Instant since30d) {
    long outstanding = byCustomer.values().stream().mapToLong(LoyaltyRecord::pointsBalance).sum();
    long cust = byCustomer.size();
    BigDecimal avg =
        cust == 0
            ? BigDecimal.ZERO
            : BigDecimal.valueOf(outstanding)
                .divide(BigDecimal.valueOf(cust), 0, java.math.RoundingMode.HALF_UP);
    Map<String, Long> tiers = new LinkedHashMap<>();
    tiers.put("NONE", 0L);
    tiers.put("SILVER", 0L);
    tiers.put("GOLD", 0L);
    tiers.put("PLATINUM", 0L);
    for (LoyaltyRecord r : byCustomer.values()) {
      tiers.merge(r.tier(), 1L, Long::sum);
    }
    long earned =
        transactions.stream()
            .filter(t -> t.type() == LoyaltyTxType.EARN && !t.createdAt().isBefore(since30d))
            .mapToLong(LoyaltyTxRecord::points)
            .sum();
    long redeemed =
        Math.abs(
            transactions.stream()
                .filter(t -> t.type() == LoyaltyTxType.REDEEM && !t.createdAt().isBefore(since30d))
                .mapToLong(LoyaltyTxRecord::points)
                .sum());
    long expired =
        Math.abs(
            transactions.stream()
                .filter(t -> t.type() == LoyaltyTxType.EXPIRE && !t.createdAt().isBefore(since30d))
                .mapToLong(LoyaltyTxRecord::points)
                .sum());
    return new OverviewStats(outstanding, avg, Map.copyOf(tiers), earned, redeemed, expired);
  }

  public List<LoyaltyTxRecord> allTransactions() {
    return List.copyOf(transactions);
  }
}
