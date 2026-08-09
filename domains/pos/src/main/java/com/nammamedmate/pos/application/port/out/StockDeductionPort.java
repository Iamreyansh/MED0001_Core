package com.nammamedmate.pos.application.port.out;

import java.time.Instant;
import java.util.UUID;

/** Stock deduction on checkout — bridged to inventory in apps/api. */
public interface StockDeductionPort {

  /**
   * Atomically decrement batch qty, write SALE movement, refresh product denorm.
   *
   * @throws com.nammamedmate.kernel.error.AppException INSUFFICIENT_STOCK when race loses
   */
  void deductSale(
      UUID pharmacyId, UUID productId, UUID batchId, int quantity, UUID staffId, Instant now);
}
