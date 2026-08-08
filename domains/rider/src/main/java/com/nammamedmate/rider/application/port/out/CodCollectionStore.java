package com.nammamedmate.rider.application.port.out;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CodCollectionStore {

  record CollectionRecord(
      UUID id,
      UUID riderId,
      UUID orderId,
      long codAmountPaise,
      Instant collectedAt,
      UUID depositId,
      boolean deposited,
      Instant createdAt) {}

  record CollectionView(
      UUID orderId,
      String orderNumber,
      long codAmountPaise,
      Instant collectedAt,
      boolean deposited) {}

  void insert(CollectionRecord row);

  Optional<CollectionRecord> findByOrderId(UUID orderId);

  List<CollectionView> recentForRider(UUID riderId, int limit);

  long sumCollectedToday(UUID riderId, Instant dayStart, Instant dayEnd);

  long sumCollectedTodayAll(Instant dayStart, Instant dayEnd);

  /** Mark undeposited collections FIFO until {@code amountPaise} covered; return applied paise. */
  long markDepositedFifo(UUID riderId, UUID depositId, long amountPaise);
}
