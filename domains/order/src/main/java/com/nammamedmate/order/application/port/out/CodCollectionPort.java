package com.nammamedmate.order.application.port.out;

import java.time.Instant;
import java.util.UUID;

/**
 * Rider COD cash collection (EPIC-011/STORY-007). Composition root bridges to rider domain; order
 * module ships a no-op stub.
 */
public interface CodCollectionPort {

  /** Idempotent per order_id: insert collection + bump rider cod_in_hand. */
  void recordCollection(UUID riderId, UUID orderId, long amountPaise, Instant collectedAt);
}
