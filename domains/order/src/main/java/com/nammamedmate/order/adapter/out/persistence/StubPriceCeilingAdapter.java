package com.nammamedmate.order.adapter.out.persistence;

import com.nammamedmate.order.application.port.out.PriceCeilingPort;
import java.util.List;
import java.util.UUID;

/** ponytail: always pass — catalogue PriceCeilingGuard bridged later if needed. */
public final class StubPriceCeilingAdapter implements PriceCeilingPort {

  @Override
  public void assertWithinCeiling(UUID pharmacyId, List<Line> lines) {
    // no-op
  }
}
