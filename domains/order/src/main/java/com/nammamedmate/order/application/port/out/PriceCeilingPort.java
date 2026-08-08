package com.nammamedmate.order.application.port.out;

import java.util.List;
import java.util.UUID;

/**
 * Order-owned price ceiling check (no domain→domain dep on catalogue). Stub always passes until
 * bridged.
 */
@FunctionalInterface
public interface PriceCeilingPort {

  record Line(UUID medicineId, long unitPricePaise) {}

  /** Throws AppException when any line exceeds ceiling; stub never throws. */
  void assertWithinCeiling(UUID pharmacyId, List<Line> lines);
}
