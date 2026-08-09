package com.nammamedmate.pos.application.port.out;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** FEFO batch selection — bridged to inventory in apps/api. */
public interface PosFefoPort {

  record BatchSnapshot(
      UUID batchId,
      UUID productId,
      String batchNumber,
      LocalDate expiryDate,
      int quantityCurrent,
      long mrpPaise) {}

  Optional<BatchSnapshot> selectFefoBatch(UUID pharmacyId, UUID productId);

  List<BatchSnapshot> listEligibleBatches(UUID pharmacyId, UUID productId);

  Optional<BatchSnapshot> findBatch(UUID pharmacyId, UUID productId, UUID batchId);
}
