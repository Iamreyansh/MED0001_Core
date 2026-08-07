package com.nammamedmate.catalogue.application.port.out;

import java.util.UUID;

/**
 * Trailing-30-day order demand for a medicine.
 *
 * <p>Stub returns 0 until EPIC-010 wires real order aggregation.
 */
public interface OrderDemandPort {

  int trailing30DayOrderCount(UUID medicineId);
}
