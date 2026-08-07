package com.nammamedmate.catalogue.adapter.out.medicine;

import com.nammamedmate.catalogue.application.port.out.OrderDemandPort;
import java.util.UUID;

/** Returns 0 until EPIC-010 wires order demand. */
public class StubOrderDemandClient implements OrderDemandPort {

  @Override
  public int trailing30DayOrderCount(UUID medicineId) {
    return 0;
  }
}
