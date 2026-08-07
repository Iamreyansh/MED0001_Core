package com.nammamedmate.pharmacy.application.port.out;

import java.util.UUID;

public interface ZonePharmacyCachePort {

  void invalidate(UUID zoneId);
}
