package com.nammamedmate.customer.application.port.out;

import com.nammamedmate.customer.domain.LoyaltyTxType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public interface LoyaltyStore {

  UUID PROGRAM_SETTINGS_ID = UUID.fromString("00000000-0000-4000-8000-000000000006");

  Optional<LoyaltyRecord> findByCustomerId(UUID customerId);

  Optional<LoyaltyRecord> lockByCustomerId(UUID customerId);

  LoyaltyRecord insert(LoyaltyRecord record);

  LoyaltyRecord update(LoyaltyRecord record);

  void syncCustomerLoyaltyPoints(UUID customerId, int pointsBalance);

  LoyaltyTxRecord insertTransaction(LoyaltyTxRecord tx);

  Optional<LoyaltyTxRecord> findByReferenceAndType(UUID referenceId, LoyaltyTxType type);

  List<LoyaltyTxRecord> listTransactions(
      UUID customerId, LoyaltyTxType type, String order, int limit, int offset);

  long countTransactions(UUID customerId, LoyaltyTxType type);

  ProgramSettingsRecord getProgramSettings();

  ProgramSettingsRecord updateProgramSettings(ProgramSettingsRecord settings);

  /** Open EARN batches with remaining_points > 0, oldest first (FIFO). */
  List<LoyaltyTxRecord> findOpenEarnBatchesFifo(UUID customerId);

  void updateEarnRemaining(UUID txId, int remainingPoints);

  /** Expired open EARN batches (expires_at <= now), oldest first. */
  List<LoyaltyTxRecord> findExpiredEarnBatches(Instant now, int limit);

  OverviewStats overviewStats(Instant since30d);

  record LoyaltyRecord(
      UUID id,
      UUID customerId,
      String tier,
      int pointsBalance,
      int pointsEarnedLifetime,
      Instant updatedAt) {}

  record LoyaltyTxRecord(
      UUID id,
      UUID customerId,
      LoyaltyTxType type,
      int points,
      int pointsBalanceAfter,
      String description,
      UUID referenceId,
      Instant createdAt,
      Instant expiresAt,
      Integer remainingPoints,
      UUID adjustedBy) {

    public LoyaltyTxRecord(
        UUID id,
        UUID customerId,
        LoyaltyTxType type,
        int points,
        int pointsBalanceAfter,
        String description,
        UUID referenceId,
        Instant createdAt) {
      this(
          id,
          customerId,
          type,
          points,
          pointsBalanceAfter,
          description,
          referenceId,
          createdAt,
          null,
          null,
          null);
    }
  }

  record ProgramSettingsRecord(
      UUID id,
      int earnRateRsPerPoint,
      BigDecimal redemptionRateRsPerPoint,
      int tierSilverPts,
      int tierGoldPts,
      int tierPlatinumPts,
      int maxRedemptionPctPerOrder,
      int minPointsPerRedemption,
      int pointsExpiryDays,
      UUID updatedBy,
      Instant updatedAt) {}

  record OverviewStats(
      long totalPointsOutstanding,
      BigDecimal avgPointsPerCustomer,
      Map<String, Long> tierDistribution,
      long pointsEarnedLast30d,
      long pointsRedeemedLast30d,
      long pointsExpiredLast30d) {}
}
