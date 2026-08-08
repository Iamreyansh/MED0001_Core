package com.nammamedmate.order.adapter.out.persistence;

import com.nammamedmate.order.application.port.out.CodCollectionPort;
import java.time.Instant;
import java.util.UUID;

/** No-op until apps/api CodCollectionPort bridge is active. */
public class StubCodCollectionAdapter implements CodCollectionPort {

  @Override
  public void recordCollection(UUID riderId, UUID orderId, long amountPaise, Instant collectedAt) {
    // no-op stub
  }
}
