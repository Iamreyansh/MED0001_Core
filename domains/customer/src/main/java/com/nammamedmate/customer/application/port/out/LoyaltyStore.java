package com.nammamedmate.customer.application.port.out;

import com.nammamedmate.customer.domain.LoyaltyTxType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface LoyaltyStore {

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
      Instant createdAt) {}
}
